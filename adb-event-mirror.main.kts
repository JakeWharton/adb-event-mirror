#!/usr/bin/env -S kotlinc -script --

@file:DependsOn("com.github.ajalt:clikt:2.8.0")

import Adb_event_mirror_main.AdbEventMirrorCommand.Device.Input
import Adb_event_mirror_main.AdbEventMirrorCommand.Device.Input.Key
import Adb_event_mirror_main.AdbEventMirrorCommand.Device.Input.Touch
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
		val hostInputs = findInputDevices(host).entries.associate { it.value to it.key }
		val hostEvents = ProcessBuilder()
			.command("adb", "-s", host, "shell", "getevent")
			.start()

		val mirrorDevices = mirrors.map(::createDevice)

		println("ready!\n")

		hostEvents.inputStream
			.bufferedReader()
			.lineSequence()
			.onEach { line ->
				if (debug) {
					println("[$host] $line")
				}
			}
			.mapNotNull(eventLine::matchEntire)
			.forEach { match ->
				val (inputDevice, typeHex, codeHex, valueHex) = match.destructured
				val input = hostInputs.getValue(inputDevice)
				val type = typeHex.toLong(16)
				val code = codeHex.toLong(16)
				val value = valueHex.toLong(16)

				if (!debug) {
					println("EVENT $input $type $code $value")
				}
				for (device in mirrorDevices) {
					device.sendEvent(input, type, code, value)
				}
			}

		for (device in mirrorDevices) {
			device.detach()
		}
	}

	private fun findInputDevices(serial: String): Map<Input, String> {
		val eventDeviceProcess = ProcessBuilder()
			.command("adb", "-s", serial, "shell", "getevent -pl")
			.start()
		val output = eventDeviceProcess.inputStream.bufferedReader().readText()

		val inputDevices = mutableMapOf<Input, String>()
		var lastDevice: String? = null
		for (eventLine in output.lines()) {
			if (eventLine.startsWith("add device ")) {
				lastDevice = eventLine.substringAfter(": ")
			} else if ("ABS_MT_TOUCH" in eventLine) {
				val previous = inputDevices[Touch]
				if (previous == null || lastDevice!! < previous) {
					inputDevices[Touch] = lastDevice!!
				}
			} else if ("KEY_ESC" in eventLine) {
				val previous = inputDevices[Key]
				if (previous == null || lastDevice!! < previous) {
					inputDevices[Key] = lastDevice!!
				}
			}
		}

		if (debug) {
			println("[$serial] devices: $inputDevices")
		}
		if (inputDevices.size != 2) {
			System.err.println(output)
			throw IllegalStateException("Unable to find touch and key input devices for $serial")
		}
		return inputDevices
	}

	private interface Device {
		enum class Input { Touch, Key }
		fun sendEvent(input: Input, type: Long, code: Long, value: Long)
		fun detach()
	}

	private fun createDevice(serial: String): Device {
		val inputDevices = findInputDevices(serial)

		val process = ProcessBuilder()
			.command("adb", "-s", serial, "shell")
			.start()

		Runtime.getRuntime().addShutdownHook(thread(start = false) {
			process.destroy()
		})

		val outputStream = process.outputStream
		fun sendCommand(command: String) {
			if (debug) {
				println("[$serial] $ $command")
			}
			outputStream.write("$command\n".toByteArray())
			outputStream.flush()
		}

		sendCommand("su")

		return object : Device {
			override fun sendEvent(input: Input, type: Long, code: Long, value: Long) {
				val inputDevice = inputDevices.getValue(input)
				sendCommand("sendevent $inputDevice $type $code $value")
			}

			override fun detach() {
				sendCommand("exit") // From 'su'
				sendCommand("exit") // From 'shell'
				process.waitFor()
			}
		}
	}

	private val eventLine = Regex("""(/dev/input/[^:]+): ([0-9a-f]+) ([0-9a-f]+) ([0-9a-f]+)""")
}
