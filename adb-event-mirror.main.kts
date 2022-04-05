#!/usr/bin/env -S kotlinc -script --

@file:DependsOn("com.github.ajalt:clikt:2.8.0")
@file:DependsOn("junit:junit:4.13")

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.arguments.transformAll
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.JUnitCore
import kotlin.concurrent.thread

if (args.contentEquals(arrayOf("--test"))) {
	JUnitCore.main(AdbEventMirrorTest::class.java.name.toString())
} else {
	AdbEventMirrorCommand.main(args)
}

object AdbEventMirrorCommand : CliktCommand(name = "adb-event-mirror") {
	private val debug by option("--debug", help = "Enable debug logging").flag()
	private val showTouches by option("--show-touches").flag("--hide-touches", default = true)
	private val deviceSerials by argument(name = "DEVICE_SERIALS")
		.multiple(true)
		.transformAll { it.toSet() }

	override fun run() {
		if(deviceSerials.size < 2) {
			throw Throwable("Please pass at least 2 device serials. For example, HostSerial MirrorSerial [AnotherMirrorSerial]...")
		}
		val host = deviceSerials.first()

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
				for (mirror in mirrors) {
					mirror.sendEvent(input, type, code, value)
				}
			}

		for (mirror in mirrors) {
			mirror.detach()
		}
	}

	fun parseInputDevices(output: String): Map<Int, String> {
		fun isDeviceNumberLower(old: String, new: String): Boolean {
			val oldNumber = old.substringAfter("/event").toInt()
			val newNumber = new.substringAfter("/event").toInt()
			return newNumber < oldNumber
		}

		val inputDevices = mutableMapOf<Int, String>()
		var lastInputDevice: String? = null
		for (line in output.lines()) {
			addDeviceLine.matchEntire(line)?.let { match ->
				lastInputDevice = match.groupValues[1]
			}
			eventTypeLine.matchEntire(line)?.let { match ->
				if (lastInputDevice!!.endsWith("/event0")) {
					// Ignore 'event0' as a quick hack. TODO actually map event codes too.
					return@let
				}
				val type = match.groupValues[1].toInt(16) // TODO is this actually hex here?
				val previous = inputDevices[type]
				if (previous == null || isDeviceNumberLower(previous, lastInputDevice!!)) {
					inputDevices[type] = lastInputDevice!!
				}
			}
		}

		return inputDevices
	}

	private interface DeviceConnection {
		fun sendEvent(hostInputDevice: String, type: Int, code: Long, value: Long)
		fun detach()
	}

	private fun createConnection(serial: String): DeviceConnection {
		val inputDevicesOutput = ProcessBuilder()
			.command("adb", "-s", serial, "shell", "getevent -pl")
			.start()
			.inputStream
			.bufferedReader()
			.readText()
		val inputDevices = parseInputDevices(inputDevicesOutput)
		if (debug) {
			println("[$serial] devices: $inputDevices")
		}

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

		return object : DeviceConnection {
			private val hostInputToSelfInput = mutableMapOf<String, String>()

			override fun sendEvent(hostInputDevice: String, type: Int, code: Long, value: Long) {
				val inputDevice = hostInputToSelfInput.computeIfAbsent(hostInputDevice) {
					val result = inputDevices.getValue(type)
					if (debug) {
						println("[$serial] $result will be used for host $it")
					}
					result
				}
				sendCommand("sendevent $inputDevice $type $code $value")
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

class AdbEventMirrorTest {
	@Test fun hello() {
		val actual = AdbEventMirrorCommand.parseInputDevices("""
			|add device 1: /dev/input/event10
			|    ABS (0003): 0000  : value 0, min 0, max 32767, fuzz 0, flat 0, resolution 0
			|    SW  (0005): 0000
			|add device 2: /dev/input/event0
			|    KEY (0001): 0074
			|add device 3: /dev/input/event8
			|    ABS (0003): 0000  : value 0, min 0, max 32767, fuzz 0, flat 0, resolution 0
			|    SW  (0005): 0000
			|add device 4: /dev/input/event11
			|    ABS (0003): 0000  : value 0, min 0, max 32767, fuzz 0, flat 0, resolution 0
			|    SW  (0005): 0000
			|add device 5: /dev/input/event5
			|    ABS (0003): 0000  : value 0, min 0, max 32767, fuzz 0, flat 0, resolution 0
			|    SW  (0005): 0000
			|add device 6: /dev/input/event2
			|    ABS (0003): 0000  : value 0, min 0, max 32767, fuzz 0, flat 0, resolution 0
			|    SW  (0005): 0000
			|add device 7: /dev/input/event12
			|    ABS (0003): 0000  : value 0, min 0, max 32767, fuzz 0, flat 0, resolution 0
			|    SW  (0005): 0000
			|add device 8: /dev/input/event13
			|    KEY (0001): 0001  0002  0003  0004  0005  0006  0007  0008
			|    LED (0011): 0000  0001  0002
			|add device 9: /dev/input/event6
			|    ABS (0003): 0000  : value 0, min 0, max 32767, fuzz 0, flat 0, resolution 0
			|    SW  (0005): 0000
			|add device 10: /dev/input/event3
			|    ABS (0003): 0000  : value 0, min 0, max 32767, fuzz 0, flat 0, resolution 0
			|    SW  (0005): 0000
			|add device 11: /dev/input/event1
			|    KEY (0001): 0001  0002  0003  0004  0005  0006  0007  0008
			|    MSC (0004): 0004
			|    LED (0011): 0000  0001  0002
			|add device 12: /dev/input/event7
			|    ABS (0003): 0000  : value 0, min 0, max 32767, fuzz 0, flat 0, resolution 0
			|    SW  (0005): 0000
			|add device 13: /dev/input/event4
			|    ABS (0003): 0000  : value 0, min 0, max 32767, fuzz 0, flat 0, resolution 0
			|    SW  (0005): 0000
			|add device 14: /dev/input/event9
			|    ABS (0003): 0000  : value 0, min 0, max 32767, fuzz 0, flat 0, resolution 0
			|    SW  (0005): 0000
			|""".trimMargin())
		val expected = mapOf(
			1 to "/dev/input/event1",
			3 to "/dev/input/event2",
			4 to "/dev/input/event1",
			17 to "/dev/input/event1"
		)
		assertEquals(expected, actual)
	}
}
