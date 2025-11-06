package com.example.users

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
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

	@Test
	fun `role check constraint rejects invalid role`() {
		Assertions.assertThrows(Exception::class.java) {
			transaction {
				UsersTable.insert {
					it[id] = UUID.randomUUID()
					it[email] = "r@ex.com"
					it[passwordHash] = "hash"
					it[fullName] = "User"
					it[role] = "GUEST"
				}
			}
		}
	}

	@Test
	fun `update full name keeps createdAt`() {
		val id = UUID.randomUUID()
		val createdAt = transaction {
			UsersTable.insert {
				it[UsersTable.id] = id
				it[email] = "upd@ex.com"
				it[passwordHash] = "h"
				it[fullName] = "Name1"
				it[role] = "USER"
			}
			UsersTable.slice(UsersTable.createdAt).select { UsersTable.id eq id }.first()[UsersTable.createdAt]
		}
		transaction {
			UsersTable.update({ UsersTable.id eq id }) {
				it[fullName] = "Name2"
			}
		}
		val row = transaction { UsersTable.select { UsersTable.id eq id }.first() }
		org.junit.jupiter.api.Assertions.assertEquals("Name2", row[UsersTable.fullName])
		org.junit.jupiter.api.Assertions.assertEquals(createdAt, row[UsersTable.createdAt])
	}
}


