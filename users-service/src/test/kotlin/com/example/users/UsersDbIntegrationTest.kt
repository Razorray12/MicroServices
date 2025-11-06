package com.example.users

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.containers.PostgreSQLContainer
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.util.UUID

@Testcontainers
class UsersDbIntegrationTest {

	companion object {
		@Container
		@JvmStatic
		val postgres: PostgreSQLContainer<Nothing> = PostgreSQLContainer("postgres:16-alpine")

		@BeforeAll
		@JvmStatic
		fun setup() {
			postgres.start()
			val cfg = HikariConfig().apply {
				jdbcUrl = postgres.jdbcUrl
				username = postgres.username
				password = postgres.password
				driverClassName = "org.postgresql.Driver"
				maximumPoolSize = 3
			}
			Database.connect(HikariDataSource(cfg))
			transaction {
				exec("CREATE SCHEMA IF NOT EXISTS users")
				SchemaUtils.create(UsersTable)
			}
		}

		@AfterAll
		@JvmStatic
		fun tearDown() {
			postgres.stop()
		}
	}

	@Test
	fun `can insert and fetch user`() {
		val userId = UUID.randomUUID()
		transaction {
			UsersTable.insert {
				it[id] = userId
				it[email] = "test@example.com"
				it[passwordHash] = "hash"
				it[fullName] = "Test User"
				it[role] = "USER"
			}
		}
		val row = transaction { UsersTable.select { UsersTable.id eq userId }.first() }
		assertEquals("test@example.com", row[UsersTable.email])
		assertNotNull(row[UsersTable.createdAt])
	}

	@Test
	fun `unique email constraint prevents duplicates`() {
		Assertions.assertThrows(Exception::class.java) {
			transaction {
				UsersTable.insert {
					it[id] = UUID.randomUUID()
					it[email] = "dup@example.com"
					it[passwordHash] = "hash"
					it[fullName] = "User One"
					it[role] = "USER"
				}
				UsersTable.insert {
					it[id] = UUID.randomUUID()
					it[email] = "dup@example.com"
					it[passwordHash] = "hash"
					it[fullName] = "User Two"
					it[role] = "USER"
				}
			}
		}
	}
}


