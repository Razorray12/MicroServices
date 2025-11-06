package com.example.accounts

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.rabbitmq.client.*
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.javatime.CurrentTimestamp
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.transactions.transaction
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.math.BigDecimal
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.time.Instant
import java.util.*

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8082
    embeddedServer(Netty, port = port, host = "0.0.0.0") {
        module()
    }.start(wait = true)
}

fun Application.module() {
    install(ContentNegotiation) { json() }

    val dbUrl = System.getenv("DB_URL") ?: error("DB_URL is required")
    val dbUser = System.getenv("DB_USER") ?: error("DB_USER is required")
    val dbPassword = System.getenv("DB_PASSWORD") ?: error("DB_PASSWORD is required")
    val jwtSecret = System.getenv("JWT_SECRET") ?: error("JWT_SECRET is required")
    val rabbitUrl = System.getenv("RABBIT_URL") ?: error("RABBIT_URL is required")

    val dataSource = hikari(dbUrl, dbUser, dbPassword)
    Database.connect(dataSource)
    migrateAccounts()

    val rabbitChannel = setupRabbit(rabbitUrl)
    startUserRegisteredConsumer(rabbitChannel) // subscribe to user.registered

    install(Authentication) {
        jwt("auth-jwt") {
            val algorithm = Algorithm.HMAC256(jwtSecret)
            verifier(JWT.require(algorithm).build())
            validate { credential ->
                if (credential.payload.subject != null) JWTPrincipal(credential.payload) else null
            }
        }
    }

    routing {
        authenticate("auth-jwt") {
            route("/api") {
                post("/accounts") {
                    val principal = call.principal<JWTPrincipal>()!!
                    val ownerId = UUID.fromString(principal.payload.subject!!)
					val req = call.receive<OpenAccountRequest>()
					val currencyRaw = req.currency.trim()
					if (!isValidCurrencyCode(currencyRaw)) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "currency must be 3-letter code"))
                        return@post
                    }
					val currency = currencyRaw.uppercase()
                    val accountId = UUID.randomUUID()
                    transaction {
                        AccountsTable.insert {
                            it[id] = accountId
                            it[ownerIdCol] = ownerId
                            it[AccountsTable.currency] = currency
                            it[balance] = BigDecimal("0.00")
                            it[status] = "ACTIVE"
                        }
                    }
                    val event = AccountOpenedEvent(
                        event = "account.opened",
                        accountId = accountId.toString(),
                        ownerId = ownerId.toString(),
                        timestamp = Instant.now().toString()
                    )
                    rabbitChannel.basicPublish(
                        "bank.events",
                        "account.opened",
                        null,
                        Json.encodeToString(event).toByteArray()
                    )
                    call.respond(HttpStatusCode.Created, mapOf("id" to accountId.toString()))
                }

                get("/accounts/{id}/balance") {
                    val principal = call.principal<JWTPrincipal>()!!
                    val ownerId = UUID.fromString(principal.payload.subject!!)
                    val accountId = call.parameters["id"]?.let(UUID::fromString)
                    if (accountId == null) {
                        call.respond(HttpStatusCode.BadRequest)
                        return@get
                    }
                    val row = transaction { AccountsTable.select { AccountsTable.id eq accountId }.firstOrNull() }
                    if (row == null || row[AccountsTable.ownerIdCol] != ownerId) {
                        call.respond(HttpStatusCode.NotFound)
                        return@get
                    }
                    call.respond(
                        BalanceResponse(
                            accountId = accountId.toString(),
                            currency = row[AccountsTable.currency],
                            balance = row[AccountsTable.balance].toPlainString()
                        )
                    )
                }

                get("/accounts/{id}/cards") {
                    val principal = call.principal<JWTPrincipal>()!!
                    val ownerId = UUID.fromString(principal.payload.subject!!)
                    val accountId = call.parameters["id"]?.let(UUID::fromString)
                    if (accountId == null) {
                        call.respond(HttpStatusCode.BadRequest)
                        return@get
                    }
                    val acc = transaction { AccountsTable.select { AccountsTable.id eq accountId }.firstOrNull() }
                    if (acc == null || acc[AccountsTable.ownerIdCol] != ownerId) {
                        call.respond(HttpStatusCode.NotFound)
                        return@get
                    }
                    val cards = transaction {
                        CardsTable.select { CardsTable.accountId eq accountId }
                            .map { CardResponse(it[CardsTable.id].value.toString(), it[CardsTable.pan], it[CardsTable.holder], it[CardsTable.status]) }
                    }
                    call.respond(cards)
                }

                post("/accounts/{id}/cards") {
                    val principal = call.principal<JWTPrincipal>()!!
                    val ownerId = UUID.fromString(principal.payload.subject!!)
                    val accountId = call.parameters["id"]?.let(UUID::fromString)
                    if (accountId == null) {
                        call.respond(HttpStatusCode.BadRequest)
                        return@post
                    }
                    val req = call.receive<IssueCardRequest>()
                    val card = CardService(rabbitChannel).issueCard(accountId, ownerId, req.holder)
                    call.respond(HttpStatusCode.Created, card)
                }

                delete("/cards/{card_id}") {
                    val principal = call.principal<JWTPrincipal>()!!
                    val ownerId = UUID.fromString(principal.payload.subject!!)
                    val cardId = call.parameters["card_id"]?.let(UUID::fromString)
                    if (cardId == null) {
                        call.respond(HttpStatusCode.BadRequest)
                        return@delete
                    }
                    val updated = transaction {
                        val card = CardsTable.select { CardsTable.id eq cardId }.firstOrNull()
                        if (card == null) return@transaction false
                        val accountId = card[CardsTable.accountId]
                        val acc = AccountsTable.select { AccountsTable.id eq accountId }.first()
                        if (acc[AccountsTable.ownerIdCol] != ownerId) return@transaction false
                        CardsTable.update({ CardsTable.id eq cardId }) { it[status] = "DELETED" }
                        true
                    }
                    if (!updated) {
                        call.respond(HttpStatusCode.NotFound)
                        return@delete
                    }
                    val event = CardEvent(
                        event = "card.deleted",
                        accountId = null,
                        ownerId = ownerId.toString(),
                        cardId = cardId.toString(),
                        timestamp = Instant.now().toString()
                    )
                    rabbitChannel.basicPublish(
                        "bank.events",
                        "card.deleted",
                        null,
                        Json.encodeToString(event).toByteArray()
                    )
                    call.respond(HttpStatusCode.OK)
                }
            }
        }
    }
}

fun isValidCurrencyCode(input: String): Boolean {
	val trimmed = input.trim()
	if (trimmed.length != 3) return false
	return trimmed.all { it in 'A'..'Z' || it in 'a'..'z' }
}

class CardService(private val rabbitChannel: Channel) {
    private val secureRandom = SecureRandom()

    // route → CardController.issueCard() → CardService.issueCard()
    //     → validateAccountActive() → generatePan() → saveCard() → publishCardIssued()
    fun issueCard(accountId: UUID, ownerId: UUID, holder: String): CardResponse {
        // Nested functions per requirement
        fun validateAccountActive() {
            val acc = transaction { AccountsTable.select { AccountsTable.id eq accountId }.firstOrNull() }
            if (acc == null || acc[AccountsTable.ownerIdCol] != ownerId || acc[AccountsTable.status] != "ACTIVE") {
                throw IllegalStateException("Account not found or inactive or not owned by user")
            }
        }

        fun generatePan(): String {
            fun randomDigits(n: Int): String = (1..n).joinToString("") { secureRandom.nextInt(10).toString() }
            return generateUniquePan({ randomDigits(it) }) { candidate ->
                transaction { CardsTable.select { CardsTable.pan eq candidate }.any() }
            }
        }

        fun saveCard(pan: String): UUID {
            val cardId = UUID.randomUUID()
            transaction {
                CardsTable.insert {
                    it[id] = cardId
                    it[CardsTable.accountId] = accountId
                    it[CardsTable.pan] = pan
                    it[CardsTable.holder] = holder
                    it[status] = "ISSUED"
                }
            }
            return cardId
        }

        fun publishCardIssued(cardId: UUID) {
            val event = CardEvent(
                event = "card.issued",
                accountId = accountId.toString(),
                ownerId = ownerId.toString(),
                cardId = cardId.toString(),
                timestamp = Instant.now().toString()
            )
            rabbitChannel.basicPublish(
                "bank.events",
                "card.issued",
                null,
                Json.encodeToString(event).toByteArray()
            )
        }

        validateAccountActive()
        val pan = generatePan()
        val cardId = saveCard(pan)
        publishCardIssued(cardId)

        return CardResponse(cardId.toString(), pan, holder, "ISSUED")
    }
}

// Pure function extracted for unit testing: generates 16-digit PAN starting with 4000
fun generateUniquePan(randomDigits: (Int) -> String, existsPan: (String) -> Boolean): String {
    var pan: String
    do {
        pan = "4000" + randomDigits(12)
    } while (existsPan(pan))
    return pan
}

private fun hikari(dbUrl: String, dbUser: String, dbPassword: String): HikariDataSource {
    val config = HikariConfig().apply {
        jdbcUrl = dbUrl
        username = dbUser
        password = dbPassword
        maximumPoolSize = 10
        driverClassName = "org.postgresql.Driver"
    }
    return HikariDataSource(config)
}

private fun migrateAccounts() {
    transaction {
        exec("CREATE SCHEMA IF NOT EXISTS accounts")
        SchemaUtils.create(AccountsTable, CardsTable)
        // Ensure CHECK constraints
        exec(
            """
            DO $$
            BEGIN
                IF NOT EXISTS (
                    SELECT 1 FROM information_schema.table_constraints
                    WHERE table_schema='accounts' AND table_name='accounts' AND constraint_name='accounts_status_check'
                ) THEN
                    ALTER TABLE accounts.accounts ADD CONSTRAINT accounts_status_check CHECK (status IN ('ACTIVE','CLOSED'));
                END IF;
            END$$;
            """.trimIndent()
        )
        exec(
            """
            DO $$
            BEGIN
                IF NOT EXISTS (
                    SELECT 1 FROM information_schema.table_constraints
                    WHERE table_schema='accounts' AND table_name='cards' AND constraint_name='cards_status_check'
                ) THEN
                    ALTER TABLE accounts.cards ADD CONSTRAINT cards_status_check CHECK (status IN ('ISSUED','DELETED'));
                END IF;
            END$$;
            """.trimIndent()
        )
    }
}

private fun setupRabbit(url: String): Channel {
    val factory = ConnectionFactory()
    factory.setUri(url)
    factory.requestedHeartbeat = 30
    factory.isAutomaticRecoveryEnabled = true
    factory.networkRecoveryInterval = 5000

    val startTime = System.currentTimeMillis()
    var attempt = 0
    var lastError: Exception? = null
    while (System.currentTimeMillis() - startTime < 60_000) {
        try {
            val connection = factory.newConnection()
            val channel = connection.createChannel()
            channel.exchangeDeclare("bank.events", BuiltinExchangeType.TOPIC, true)
            return channel
        } catch (e: Exception) {
            lastError = e
            attempt += 1
            Thread.sleep(minOf(2000L * attempt, 5000L))
        }
    }
    throw IOException("RabbitMQ connection failed after retries", lastError)
}

private fun startUserRegisteredConsumer(channel: Channel) {
    val queue = channel.queueDeclare("accounts.user.registered", true, false, false, null).queue
    channel.queueBind(queue, "bank.events", "user.registered")
    val deliver = DeliverCallback { _, delivery ->
        val message = String(delivery.body, StandardCharsets.UTF_8)
        println("[accounts-service] received user.registered: $message")
    }
    channel.basicConsume(queue, true, deliver, CancelCallback { })
}

object AccountsTable : UUIDTable(name = "accounts.accounts") {
    val ownerIdCol = uuid("owner_id")
    val currency = varchar("currency", 3)
    val balance = decimal("balance", 18, 2).default(BigDecimal("0.00"))
    val status = varchar("status", 16).default("ACTIVE")
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp())
}

object CardsTable : UUIDTable(name = "accounts.cards") {
    val accountId = reference("account_id", AccountsTable)
    val pan = varchar("pan", 32).uniqueIndex()
    val holder = varchar("holder", 255)
    val status = varchar("status", 16).default("ISSUED")
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp())
}

@Serializable
data class OpenAccountRequest(val currency: String)

@Serializable
data class BalanceResponse(val accountId: String, val currency: String, val balance: String)

@Serializable
data class CardResponse(val id: String, val pan: String, val holder: String, val status: String)

@Serializable
data class IssueCardRequest(val holder: String)

@Serializable
data class CardEvent(
    val event: String,
    val accountId: String?,
    val ownerId: String,
    val cardId: String,
    val timestamp: String
)

@Serializable
data class AccountOpenedEvent(
    val event: String,
    val accountId: String,
    val ownerId: String,
    val timestamp: String
)


