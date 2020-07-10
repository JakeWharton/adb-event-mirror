#!/usr/bin/env kotlin

import kotlin.concurrent.thread

if (args.isEmpty()) {
	System.err.println("WARNING: No serial number arguments specified! Writing to stdout only.")
}

private val devices = args.toSet().map { serial ->
	val device = findEventDevice(serial)
	println("$serial using $device")

	createDevice(serial, device)
}
println("ready!\n")

val eventLine = Regex("""/dev/input/[^:]+: ([0-9a-f]+) ([0-9a-f]+) ([0-9a-f]+)""")
System.`in`.bufferedReader()
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

fun findEventDevice(serial: String): String {
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

interface Device {
	fun sendEvent(type: Long, code: Long, value: Long)
	fun detach()
}

fun createDevice(serial: String, device: String): Device {
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
