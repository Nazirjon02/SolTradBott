package tj.khujand.solana.trading.bot.server

import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json

// Минимальный каркас сервера (этап 1). REST v1 / WebSocket / Bearer-auth — этап 5.
fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080

    embeddedServer(Netty, port = port) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }

        routing {
            get("/health") { call.respond(mapOf("status" to "ok", "bot" to "DRX")) }
        }
    }.start(wait = true)
}
