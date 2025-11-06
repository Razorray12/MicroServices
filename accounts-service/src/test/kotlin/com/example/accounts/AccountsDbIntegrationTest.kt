package com.example.accounts

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
import java.math.BigDecimal
import java.util.UUID

@Testcontainers
class AccountsDbIntegrationTest {

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
				exec("CREATE SCHEMA IF NOT EXISTS accounts")
				SchemaUtils.create(AccountsTable, CardsTable)
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

		@AfterAll
		@JvmStatic
		fun tearDown() {
			postgres.stop()
		}
	}

	@Test
	fun `can insert account and card`() {
		val ownerId = UUID.randomUUID()
		val accountId = UUID.randomUUID()
		transaction {
			AccountsTable.insert {
				it[id] = accountId
				it[ownerIdCol] = ownerId
				it[currency] = "USD"
				it[balance] = BigDecimal("0.00")
				it[status] = "ACTIVE"
			}
		}
		val acc = transaction { AccountsTable.select { AccountsTable.id eq accountId }.first() }
		assertEquals("USD", acc[AccountsTable.currency])
		assertNotNull(acc[AccountsTable.createdAt])

		val cardId = UUID.randomUUID()
		transaction {
			CardsTable.insert {
				it[id] = cardId
				it[CardsTable.accountId] = accountId
				it[pan] = "4000123412341234"
				it[holder] = "JOHN DOE"
				it[status] = "ISSUED"
			}
		}
		val card = transaction { CardsTable.select { CardsTable.id eq cardId }.first() }
		assertEquals("ISSUED", card[CardsTable.status])
	}

	@Test
	fun `unique pan constraint prevents duplicates`() {
		Assertions.assertThrows(Exception::class.java) {
			transaction {
				val accountId = UUID.randomUUID()
				AccountsTable.insert {
					it[id] = accountId
					it[ownerIdCol] = UUID.randomUUID()
					it[currency] = "EUR"
					it[balance] = BigDecimal("0.00")
					it[status] = "ACTIVE"
				}
				val pan = "4000999988887777"
				CardsTable.insert {
					it[id] = UUID.randomUUID()
					it[CardsTable.accountId] = accountId
					it[CardsTable.pan] = pan
					it[holder] = "A"
					it[status] = "ISSUED"
				}
				CardsTable.insert {
					it[id] = UUID.randomUUID()
					it[CardsTable.accountId] = accountId
					it[CardsTable.pan] = pan
					it[holder] = "B"
					it[status] = "ISSUED"
				}
			}
		}
	}

	@Test
	fun `card insert with non-existing account fails due to FK`() {
		Assertions.assertThrows(Exception::class.java) {
			transaction {
				CardsTable.insert {
					it[id] = UUID.randomUUID()
					it[CardsTable.accountId] = UUID.randomUUID()
					it[pan] = "4000123499999999"
					it[holder] = "X"
					it[status] = "ISSUED"
				}
			}
		}
	}

	@Test
	fun `check constraint rejects invalid statuses`() {
		Assertions.assertThrows(Exception::class.java) {
			transaction {
				AccountsTable.insert {
					it[id] = UUID.randomUUID()
					it[ownerIdCol] = UUID.randomUUID()
					it[currency] = "USD"
					it[balance] = BigDecimal("0.00")
					it[status] = "FROZEN"
				}
			}
		}
		Assertions.assertThrows(Exception::class.java) {
			transaction {
				val accountId = UUID.randomUUID()
				AccountsTable.insert {
					it[id] = accountId
					it[ownerIdCol] = UUID.randomUUID()
					it[currency] = "USD"
					it[balance] = BigDecimal("0.00")
					it[status] = "ACTIVE"
				}
				CardsTable.insert {
					it[id] = UUID.randomUUID()
					it[CardsTable.accountId] = accountId
					it[pan] = "4000123400000000"
					it[holder] = "Y"
					it[status] = "BLOCKED"
				}
			}
		}
	}

	@Test
	fun `delete card updates status to DELETED`() {
		val accountId = UUID.randomUUID()
		val cardId = UUID.randomUUID()
		transaction {
			AccountsTable.insert {
				it[id] = accountId
				it[ownerIdCol] = UUID.randomUUID()
				it[currency] = "USD"
				it[balance] = BigDecimal("0.00")
				it[status] = "ACTIVE"
			}
			CardsTable.insert {
				it[id] = cardId
				it[CardsTable.accountId] = accountId
				it[pan] = "4000123499990000"
				it[holder] = "Z"
				it[status] = "ISSUED"
			}
		}
		transaction {
			CardsTable.update({ CardsTable.id eq cardId }) {
				it[status] = "DELETED"
			}
		}
		val row = transaction { CardsTable.select { CardsTable.id eq cardId }.first() }
		assertEquals("DELETED", row[CardsTable.status])
	}
}


