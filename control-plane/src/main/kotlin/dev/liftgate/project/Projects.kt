package dev.liftgate.project

import dev.liftgate.build.orphanRepositories
import dev.liftgate.db.Db
import dev.liftgate.db.Environments
import dev.liftgate.db.GitHubInstallations
import dev.liftgate.db.Projects as ProjectsTable
import dev.liftgate.db.sql
import dev.liftgate.db.toEnum
import dev.liftgate.events.Subject
import dev.liftgate.events.enqueue
import dev.liftgate.org.Limits
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.innerJoin
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.insertReturning
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.updateReturning
import java.util.UUID

fun ResultRow.toProject() = Project(
    this[ProjectsTable.id],
    this[ProjectsTable.orgId],
    this[ProjectsTable.slug],
    this[ProjectsTable.name],
    this[ProjectsTable.repoFullName],
    this[ProjectsTable.repoDefaultBranch],
    this[ProjectsTable.installationId],
    this[ProjectsTable.importedByLogin],
    this[ProjectsTable.previewsEnabled],
    this[ProjectsTable.previewBaseEnvironmentId],
)

fun ResultRow.toEnvironment() = Environment(
    this[Environments.id],
    this[Environments.projectId],
    this[Environments.slug],
    this[Environments.name],
    this[Environments.kind].toEnum(),
    this[Environments.branch],
    this[Environments.namespace],
    this[Environments.pullRequest],
)

fun namespaceFor(environmentId: UUID) = "env-" + environmentId.toString().replace("-", "").take(12)

fun JdbcTransaction.enqueueTeardown(where: () -> Op<Boolean>) = (Environments innerJoin ProjectsTable).select(Environments.namespace).where(where)
    .forEach { enqueue(Subject.TEARDOWN_REQUESTED, buildJsonObject { put("namespace", it[Environments.namespace]) }) }

fun JdbcTransaction.deleteEnvironments(where: () -> Op<Boolean>) {
    enqueueTeardown(where)
    orphanRepositories(where())
    Environments.deleteWhere { where() }
}

/**
 * @author Dean
 * @date 9/17/2026
 */
class Projects(private val db: Db, private val limits: Limits = Limits()) {
    suspend fun create(orgId: UUID, slug: String, name: String, repoFullName: String, installationId: Long, importedByLogin: String? = null, defaultBranch: String = "main"): Project = db.tx {
        limits.project(orgId)
        GitHubInstallations.insertIgnore {
            it[id] = installationId
            it[GitHubInstallations.orgId] = orgId
            it[accountLogin] = repoFullName.substringBefore('/')
        }
        val project = ProjectsTable.insertReturning {
            it[id] = UUID.randomUUID()
            it[ProjectsTable.orgId] = orgId
            it[ProjectsTable.slug] = slug
            it[ProjectsTable.name] = name
            it[ProjectsTable.repoFullName] = repoFullName
            it[ProjectsTable.repoDefaultBranch] = defaultBranch
            it[ProjectsTable.installationId] = installationId
            it[ProjectsTable.importedByLogin] = importedByLogin
        }.single().toProject()
        limits.environment(project.id)
        insertEnvironment(project.id, "production", "Production", EnvironmentKind.PRODUCTION, project.repoDefaultBranch)
        project
    }

    suspend fun byId(id: UUID): Project? = db.tx { ProjectsTable.selectAll().where { ProjectsTable.id eq id }.singleOrNull()?.toProject() }

    suspend fun forOrg(orgId: UUID): List<Project> = db.tx {
        ProjectsTable.selectAll().where { ProjectsTable.orgId eq orgId }.orderBy(ProjectsTable.slug).map { it.toProject() }
    }

    suspend fun delete(id: UUID) {
        db.tx {
            enqueueTeardown { ProjectsTable.id eq id }
            orphanRepositories(ProjectsTable.id eq id)
            ProjectsTable.deleteWhere { ProjectsTable.id eq id }
        }
    }

    suspend fun update(id: UUID, settings: PreviewSettings): Project = db.tx {
        ProjectsTable.updateReturning(ProjectsTable.columns, { ProjectsTable.id eq id }) {
            it[previewsEnabled] = settings.previewsEnabled
            it[previewBaseEnvironmentId] = settings.previewBaseEnvironmentId
        }.single().toProject()
    }

    suspend fun forRepo(installationId: Long, repoFullName: String): List<Project> = db.tx {
        ProjectsTable.selectAll().where { (ProjectsTable.installationId eq installationId) and (ProjectsTable.repoFullName eq repoFullName) }.map { it.toProject() }
    }

    suspend fun createEnvironment(projectId: UUID, slug: String, name: String, kind: EnvironmentKind, branch: String): Environment = db.tx {
        limits.environment(projectId)
        insertEnvironment(projectId, slug, name, kind, branch)
    }

    suspend fun deleteEnvironment(id: UUID) {
        db.tx { deleteEnvironments { Environments.id eq id } }
    }

    suspend fun environment(id: UUID): Environment? = db.tx { Environments.selectAll().where { Environments.id eq id }.singleOrNull()?.toEnvironment() }

    suspend fun environments(projectId: UUID): List<Environment> = db.tx {
        Environments.selectAll().where { Environments.projectId eq projectId }.orderBy(Environments.slug).map { it.toEnvironment() }
    }

    suspend fun environmentsForRepo(installationId: Long, repoFullName: String, branch: String): List<Environment> = db.tx {
        (Environments innerJoin ProjectsTable).selectAll()
            .where { (ProjectsTable.installationId eq installationId) and (ProjectsTable.repoFullName eq repoFullName) and (Environments.branch eq branch) and Environments.pullRequest.isNull() }
            .map { it.toEnvironment() }
    }

    fun insertPreview(projectId: UUID, number: Int, branch: String): Environment {
        limits.preview(projectId)
        return insertEnvironment(projectId, "pr-$number", "PR #$number", EnvironmentKind.PREVIEW, branch, number)
    }

    private fun insertEnvironment(projectId: UUID, slug: String, name: String, kind: EnvironmentKind, branch: String, pullRequest: Int? = null): Environment {
        val id = UUID.randomUUID()
        return Environments.insertReturning {
            it[Environments.id] = id
            it[Environments.projectId] = projectId
            it[Environments.slug] = slug
            it[Environments.name] = name
            it[Environments.kind] = kind.sql
            it[Environments.branch] = branch
            it[namespace] = namespaceFor(id)
            it[Environments.pullRequest] = pullRequest
        }.single().toEnvironment()
    }
}
