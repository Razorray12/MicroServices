package com.example.users

import at.favre.lib.crypto.bcrypt.BCrypt
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.rabbitmq.client.BuiltinExchangeType
import com.rabbitmq.client.Channel
import com.rabbitmq.client.ConnectionFactory
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
import java.time.Instant
import java.io.IOException
import java.util.*

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8081
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
    migrateUsers()

    val rabbitChannel = setupRabbit(rabbitUrl)

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
        route("/api") {
            route("/auth") {
                post("/register") {
	                    val body = call.receive<RegisterRequest>()
	                    val email = normalizeEmail(body.email)
                    val fullName = body.fullName.trim()
                    if (email.isBlank() || body.password.isBlank() || fullName.isBlank()) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid input"))
                        return@post
                    }
                    val existing = transaction { UsersTable.select { UsersTable.email eq email }.firstOrNull() }
                    if (existing != null) {
                        call.respond(HttpStatusCode.Conflict, mapOf("error" to "Email already registered"))
                        return@post
                    }
                    val userId = UUID.randomUUID()
                    val passwordHash = hashPassword(body.password)
                    val createdAt = transaction {
                        UsersTable.insert {
                            it[id] = userId
                            it[UsersTable.email] = email
                            it[UsersTable.passwordHash] = passwordHash
                            it[UsersTable.fullName] = fullName
                            it[UsersTable.role] = "USER"
                        }
                        val ts = UsersTable
                            .slice(UsersTable.createdAt)
                            .select { UsersTable.id eq userId }
                            .first()[UsersTable.createdAt]
                        ts
                    }

                    // Publish user.registered event
                    val event = UserRegisteredEvent(
                        userId = userId.toString(),
                        email = email,
                        createdAt = createdAt.toString()
                    )
                    rabbitChannel.basicPublish(
                        "bank.events",
                        "user.registered",
                        null,
                        Json.encodeToString(event).toByteArray()
                    )

                    call.respond(HttpStatusCode.Created, mapOf("id" to userId.toString()))
                }

                post("/login") {
                    val body = call.receive<LoginRequest>()
	                    val email = normalizeEmail(body.email)
                    val row = transaction { UsersTable.select { UsersTable.email eq email }.firstOrNull() }
                    if (row == null) {
                        call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid credentials"))
                        return@post
                    }
                    val verified = verifyPassword(body.password, row[UsersTable.passwordHash])
                    if (!verified) {
                        call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid credentials"))
                        return@post
                    }
                    val userId = row[UsersTable.id].value
                    val role = row[UsersTable.role]
                    val token = createToken(userId.toString(), role, jwtSecret, 3600)

                    call.respond(TokenResponse(access_token = token))
                }
            }
            authenticate("auth-jwt") {
                get("/users/me") {
                    val principal = call.principal<JWTPrincipal>() ?: run {
                        call.respond(HttpStatusCode.Unauthorized)
                        return@get
                    }
                    val userId = principal.payload.subject!!
                    val row = transaction { UsersTable.select { UsersTable.id eq UUID.fromString(userId) }.firstOrNull() }
                    if (row == null) {
                        call.respond(HttpStatusCode.NotFound)
                        return@get
                    }
                    val resp = UserResponse(
                        id = row[UsersTable.id].value.toString(),
                        email = row[UsersTable.email],
                        fullName = row[UsersTable.fullName],
                        role = row[UsersTable.role],
                        createdAt = row[UsersTable.createdAt].toString()
                    )
                    call.respond(resp)
                }
            }
        }
    }
}

fun normalizeEmail(raw: String): String = raw.trim().lowercase()

fun hashPassword(password: String): String = BCrypt.withDefaults().hashToString(12, password.toCharArray())

fun verifyPassword(password: String, hash: String): Boolean = BCrypt.verifyer().verify(password.toCharArray(), hash).verified

fun createToken(subject: String, role: String, secret: String, ttlSeconds: Long): String =
    JWT.create()
        .withSubject(subject)
        .withClaim("role", role)
        .withExpiresAt(Date.from(Instant.now().plusSeconds(ttlSeconds)))
        .sign(Algorithm.HMAC256(secret))

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

private fun migrateUsers() {
    transaction {
        exec("CREATE SCHEMA IF NOT EXISTS users")
        SchemaUtils.create(UsersTable)
        // Ensure CHECK constraint for role
        exec(
            """
            DO $$
            BEGIN
                IF NOT EXISTS (
                    SELECT 1 FROM information_schema.table_constraints
                    WHERE table_schema='users' AND table_name='users' AND constraint_name='users_role_check'
                ) THEN
                    ALTER TABLE users.users
                    ADD CONSTRAINT users_role_check CHECK (role IN ('USER','ADMIN'));
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

object UsersTable : UUIDTable(name = "users.users") {
    val email = varchar("email", 255).uniqueIndex()
    val passwordHash = varchar("password_hash", 255)
    val fullName = varchar("full_name", 255)
    val role = varchar("role", 16)
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp())
}

@Serializable
data class RegisterRequest(val email: String, val password: String, val fullName: String)

@Serializable
data class LoginRequest(val email: String, val password: String)

@Serializable
data class TokenResponse(val access_token: String, val token_type: String = "Bearer")

@Serializable
data class UserResponse(
    val id: String,
    val email: String,
    val fullName: String,
    val role: String,
    val createdAt: String
)

@Serializable
data class UserRegisteredEvent(val userId: String, val email: String, val createdAt: String)


