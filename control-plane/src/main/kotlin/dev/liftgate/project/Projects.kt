package dev.liftgate.project

import dev.liftgate.db.Db
import dev.liftgate.db.Environments
import dev.liftgate.db.GitHubInstallations
import dev.liftgate.db.Projects as ProjectsTable
import dev.liftgate.db.sql
import dev.liftgate.db.toEnum
import dev.liftgate.events.Subject
import dev.liftgate.events.enqueue
import dev.liftgate.http.forbidden
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.innerJoin
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertReturning
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.util.UUID

fun ResultRow.toProject() = Project(
    this[ProjectsTable.id],
    this[ProjectsTable.orgId],
    this[ProjectsTable.slug],
    this[ProjectsTable.name],
    this[ProjectsTable.repoFullName],
    this[ProjectsTable.repoDefaultBranch],
    this[ProjectsTable.installationId],
)

fun ResultRow.toEnvironment() = Environment(
    this[Environments.id],
    this[Environments.projectId],
    this[Environments.slug],
    this[Environments.name],
    this[Environments.kind].toEnum(),
    this[Environments.branch],
    this[Environments.namespace],
)

fun namespaceFor(environmentId: UUID) = "env-" + environmentId.toString().replace("-", "").take(12)

/**
 * @author Dean
 * @date 9/17/2026
 */
class Projects(private val db: Db) {
    suspend fun create(orgId: UUID, slug: String, name: String, repoFullName: String, installationId: Long): Project = db.tx {
        claimInstallation(installationId, orgId, repoFullName.substringBefore('/'))
        val project = ProjectsTable.insertReturning {
            it[id] = UUID.randomUUID()
            it[ProjectsTable.orgId] = orgId
            it[ProjectsTable.slug] = slug
            it[ProjectsTable.name] = name
            it[ProjectsTable.repoFullName] = repoFullName
            it[ProjectsTable.installationId] = installationId
        }.single().toProject()
        insertEnvironment(project.id, "production", "Production", EnvironmentKind.PRODUCTION, project.repoDefaultBranch)
        project
    }

    suspend fun byId(id: UUID): Project? = db.tx { ProjectsTable.selectAll().where { ProjectsTable.id eq id }.singleOrNull()?.toProject() }

    suspend fun forOrg(orgId: UUID): List<Project> = db.tx {
        ProjectsTable.selectAll().where { ProjectsTable.orgId eq orgId }.orderBy(ProjectsTable.slug).map { it.toProject() }
    }

    suspend fun delete(id: UUID) {
        db.tx {
            Environments.select(Environments.namespace).where { Environments.projectId eq id }
                .forEach { enqueue(Subject.TEARDOWN_REQUESTED, buildJsonObject { put("namespace", it[Environments.namespace]) }) }
            ProjectsTable.deleteWhere { ProjectsTable.id eq id }
        }
    }

    suspend fun createEnvironment(projectId: UUID, slug: String, name: String, kind: EnvironmentKind, branch: String): Environment =
        db.tx { insertEnvironment(projectId, slug, name, kind, branch) }

    suspend fun environment(id: UUID): Environment? = db.tx { Environments.selectAll().where { Environments.id eq id }.singleOrNull()?.toEnvironment() }

    suspend fun environments(projectId: UUID): List<Environment> = db.tx {
        Environments.selectAll().where { Environments.projectId eq projectId }.orderBy(Environments.slug).map { it.toEnvironment() }
    }

    suspend fun environmentsForRepo(installationId: Long, repoFullName: String, branch: String): List<Environment> = db.tx {
        (Environments innerJoin ProjectsTable).selectAll()
            .where { (ProjectsTable.installationId eq installationId) and (ProjectsTable.repoFullName eq repoFullName) and (Environments.branch eq branch) }
            .map { it.toEnvironment() }
    }

    private fun claimInstallation(installationId: Long, orgId: UUID, accountLogin: String) {
        val owner = GitHubInstallations.select(GitHubInstallations.orgId)
            .where { GitHubInstallations.id eq installationId }
            .singleOrNull()?.get(GitHubInstallations.orgId)
        when (owner) {
            null -> GitHubInstallations.insert {
                it[id] = installationId
                it[GitHubInstallations.orgId] = orgId
                it[GitHubInstallations.accountLogin] = accountLogin
            }
            orgId -> Unit
            else -> forbidden()
        }
    }

    private fun insertEnvironment(projectId: UUID, slug: String, name: String, kind: EnvironmentKind, branch: String): Environment {
        val id = UUID.randomUUID()
        return Environments.insertReturning {
            it[Environments.id] = id
            it[Environments.projectId] = projectId
            it[Environments.slug] = slug
            it[Environments.name] = name
            it[Environments.kind] = kind.sql
            it[Environments.branch] = branch
            it[namespace] = namespaceFor(id)
        }.single().toEnvironment()
    }
}
