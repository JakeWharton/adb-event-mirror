#!/usr/bin/env -S kotlinc -script --

@file:DependsOn("com.github.ajalt:clikt:2.8.0")

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.arguments.transformAll
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import kotlin.concurrent.thread

AdbEventMirrorCommand.main(args)

object AdbEventMirrorCommand : CliktCommand(name = "adb-event-mirror") {
	private val debug by option("--debug", help = "Enable debug logging").flag()
	private val host by argument(name = "HOST_SERIAL")
	private val mirrors by argument(name = "MIRROR_SERIAL")
		.multiple(true)
		.transformAll { it.toSet() }

	override fun run() {
		val devices = mirrors.map { serial ->
			val device = findEventDevice(serial)
			if (debug) {
				println("$serial using $device")
			}

			createDevice(serial, device)
		}

		val hostEvents = ProcessBuilder()
			.command("adb", "-s", host, "shell", "getevent")
			.start()

		println("ready!\n")

		hostEvents.inputStream
			.bufferedReader()
			.lineSequence()
			.mapNotNull(eventLine::matchEntire)
			.forEach { match ->
				val (typeHex, codeHex, valueHex) = match.destructured
				val type = typeHex.toLong(16)
				val code = codeHex.toLong(16)
				val value = valueHex.toLong(16)

				println("EVENT $type $code $value")
				for (device in devices) {
					device.sendEvent(type, code, value)
				}
			}

		for (device in devices) {
			device.detach()
		}
	}

	private fun findEventDevice(serial: String): String {
		val eventDeviceProcess = ProcessBuilder()
			.command("adb", "-s", serial, "shell", "getevent -pl")
			.start()
		val output = eventDeviceProcess.inputStream.bufferedReader().readText()

		val devices = mutableListOf<String>()
		var lastDevice: String? = null
		for (eventLine in output.lines()) {
			if (eventLine.startsWith("add device ")) {
				lastDevice = eventLine.substringAfter(": ")
			} else if ("ABS_MT_TOUCH" in eventLine) {
				devices += lastDevice!!
			}
		}

		if (devices.isEmpty()) {
			System.err.println(output)
			throw IllegalStateException("Unable to list event devices for $serial")
		}
		return devices.min()!!
	}

	private interface Device {
		fun sendEvent(type: Long, code: Long, value: Long)
		fun detach()
	}

	private fun createDevice(serial: String, device: String): Device {
		val process = ProcessBuilder()
			.command("adb", "-s", serial, "shell")
			.start()

		Runtime.getRuntime().addShutdownHook(thread(start = false) {
			process.destroy()
		})

		val outputStream = process.outputStream
		fun sendCommand(command: String) {
			outputStream.write("$command\n".toByteArray())
			outputStream.flush()
		}

		sendCommand("su")

		return object : Device {
			override fun sendEvent(type: Long, code: Long, value: Long) {
				sendCommand("sendevent $device $type $code $value")
			}

			override fun detach() {
				sendCommand("exit") // From 'su'
				sendCommand("exit") // From 'shell'
				process.waitFor()
			}
		}
	}

	private val eventLine = Regex("""/dev/input/[^:]+: ([0-9a-f]+) ([0-9a-f]+) ([0-9a-f]+)""")
}
