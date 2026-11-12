package com.example.a2desktop.tools

import com.example.a2desktop.data.DesktopStore
import com.example.a2desktop.link.LinkClient
import com.example.a2desktop.link.LinkController
import com.example.a2desktop.link.LinkStorage
import kotlin.system.exitProcess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Pairs this PC with the phone from the command line, through exactly the same code as the Phone
 * page's "Connect and sync" button (`gradlew pair -Pa2.address="…" -Pa2.key="…"`): reads what it is
 * given the same forgiving way, tries each address, and on success saves the pairing and the phone's
 * data to `%APPDATA%\a2-desktop`, so the app opens already connected. Prints only a short summary —
 * never the key and never the phone's data itself.
 */
fun main(args: Array<String>) {
    if (args.size < 2) {
        println("usage: pair <address text> <pairing key text>")
        exitProcess(2)
    }
    val store = DesktopStore()
    val link = LinkController(store, LinkStorage(), LinkClient(), CoroutineScope(SupervisorJob()), Dispatchers.IO)

    link.connect(args[0], args[1])?.let {
        println("Not paired: $it")
        exitProcess(1)
    }
    val deadline = System.currentTimeMillis() + 60_000
    while ((link.syncing || (link.pairing == null && link.error == null)) && System.currentTimeMillis() < deadline) Thread.sleep(100)

    if (link.pairing == null || !link.reachable) {
        println("Not paired: ${link.error ?: "no answer from the phone"}")
        exitProcess(1)
    }
    println("Paired with ${link.pairing?.address} (${store.deviceName}).")
    println(
        "Received: ${store.habits.size} habits, ${store.logs.values.sumOf { it.size }} habit records, " +
            "${store.events.size} events, ${store.upcoming.size} upcoming items, ${store.sessions.size} workouts, " +
            "${store.dreams.size} dreams; date of birth ${if (store.dateOfBirth != null) "set" else "not set"}.",
    )
    exitProcess(0)
}
