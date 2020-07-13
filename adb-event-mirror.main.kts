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
	private val showTouches by option("--show-touches").flag("--hide-touches", default = true)
	private val mirrors by argument(name = "MIRROR_SERIAL")
		.multiple(true)
		.transformAll { it.toSet() }

	override fun run() {
		val mirrorDevices = mirrors.map(::createDevice)

		println("ready!\n")

		System.`in`
			.bufferedReader()
			.lineSequence()
			.onEach { line ->
				if (debug) {
					println("[host] $line")
				}
			}
			.mapNotNull(eventLine::matchEntire)
			.forEach { match ->
				val (input, typeHex, codeHex, valueHex) = match.destructured
				val type = typeHex.toInt(16)
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

	private fun parseEventTypeToInput(serial: String): Map<Int, String> {
		val eventDeviceProcess = ProcessBuilder()
			.command("adb", "-s", serial, "shell", "getevent -pl")
			.start()
		val output = eventDeviceProcess.inputStream.bufferedReader().readText()

		val inputs = mutableMapOf<Int, String>()
		var lastDevice: String? = null
		for (line in output.lines()) {
			addDeviceLine.matchEntire(line)?.let { match ->
				lastDevice = match.groupValues[1]
			}
			eventTypeLine.matchEntire(line)?.let { match ->
				if (lastDevice!!.endsWith("/event0")) {
					// Ignore 'event0' as a quick hack. TODO actually map event codes too.
					return@let
				}
				val type = match.groupValues[1].toInt(16) // TODO is this actually hex here?
				val previous = inputs[type]
				if (previous == null || lastDevice!! < previous) {
					inputs[type] = lastDevice!!
				}
			}
		}

		if (debug) {
			println("[$serial] devices: $inputs")
		}
		return inputs
	}

	private interface Device {
		fun sendEvent(hostInput: String, type: Int, code: Long, value: Long)
		fun detach()
	}

	private fun createDevice(serial: String): Device {
		val eventTypeToInput = parseEventTypeToInput(serial)

		val showTouchesOriginalValue = ProcessBuilder()
			.command("adb", "-s", serial, "shell", "settings get system show_touches")
			.start()
			.inputStream
			.bufferedReader()
			.readText()
			.trim() == "1"

		if (showTouches) {
			ProcessBuilder()
				.command("adb", "-s", serial, "shell", "settings put system show_touches 1")
				.start()
				.waitFor()
		}

		val process = ProcessBuilder()
			.command("adb", "-s", serial, "shell")
			.start()

		Runtime.getRuntime().addShutdownHook(thread(start = false) {
			process.destroy()

			if (showTouches) {
				val oldValue = if (showTouchesOriginalValue) "1" else "0"
				ProcessBuilder()
					.command("adb", "-s", serial, "shell", "settings put system show_touches $oldValue")
					.start()
					.waitFor()
			}
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
			private val hostInputToSelfInput = mutableMapOf<String, String>()

			override fun sendEvent(hostInput: String, type: Int, code: Long, value: Long) {
				val input = hostInputToSelfInput.computeIfAbsent(hostInput) {
					val result = eventTypeToInput.getValue(type)
					if (debug) {
						println("[$serial] $result will be used for host $it")
					}
					result
				}
				sendCommand("sendevent $input $type $code $value")
			}

			override fun detach() {
				sendCommand("exit") // From 'su'
				sendCommand("exit") // From 'shell'
				process.waitFor()
			}
		}
	}

	private val addDeviceLine = Regex("""add device \d+: (.+)""")
	private val eventTypeLine = Regex("""    [A-Z]+ \((\d+)\): .*""")
	private val eventLine = Regex("""(/dev/input/[^:]+): ([0-9a-f]+) ([0-9a-f]+) ([0-9a-f]+)""")
}
