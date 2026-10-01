package dev.liftgate.build

import dev.liftgate.http.json
import dev.liftgate.service.ServiceKind
import dev.liftgate.service.ServiceKind.CRON
import dev.liftgate.service.ServiceKind.STATIC
import dev.liftgate.service.ServiceKind.WEB
import dev.liftgate.service.ServiceKind.WORKER
import dev.liftgate.service.ServiceSpec
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * @author Dean
 * @date 10/1/2026
 */
class DetectorTest {
    data class Case(
        val name: String,
        val files: Map<String, String>,
        val framework: String?,
        val kind: ServiceKind = WEB,
        val builder: String = "railpack",
        val selected: Boolean = true,
        val spec: ServiceSpec.() -> ServiceSpec = { this },
        val check: (DetectedService) -> Unit = {},
    ) {
        override fun toString() = name
    }

    companion object {
        private fun pkg(deps: String = "", scripts: String = """"build":"x","start":"x"""") = """{"name":"shop","scripts":{$scripts},"dependencies":{$deps}}"""

        @JvmStatic
        fun rows() = listOf(
            Case(
                "0 Dockerfile",
                mapOf("Dockerfile" to "FROM node:24\nEXPOSE 3000\nHEALTHCHECK --interval=5s \\\n  CMD curl -f http://localhost:3000/healthz || exit 1\nCMD [\"node\", \"server.js\"]"),
                "dockerfile",
                builder = "dockerfile",
                spec = { copy(port = 3000, healthCheckPath = "/healthz") },
            ),
            Case("0 Dockerfile for development", mapOf("Dockerfile" to "FROM node:24\nENV PORT=4000\nCMD npm run dev"), "dockerfile", builder = "dockerfile", spec = { copy(port = 4000) }) {
                assertEquals(listOf("Looks like a development Dockerfile"), it.warnings)
            },
            Case("0 Dockerfile with ports out of range", mapOf("Dockerfile" to "FROM node:24\nEXPOSE 99999999999\nENV PORT=70000"), "dockerfile", builder = "dockerfile"),
            Case("1 Laravel", mapOf("composer.json" to "{}", "artisan" to ""), "laravel") { assertEquals("/up", it.healthHint) },
            Case("1 PHP", mapOf("index.php" to "<?php"), "php"),
            Case("2 Gin with a literal port", mapOf("go.mod" to "module shop\nrequire github.com/gin-gonic/gin v1.10.0", "main.go" to "r.Run(\":8081\")"), "gin", spec = { copy(port = 8081) }) {
                assertEquals("./out", it.defaults.start)
            },
            Case("2 Go reading PORT", mapOf("go.mod" to "module shop", "cmd/api/main.go" to "http.ListenAndServe(\":\"+os.Getenv(\"PORT\"), nil) // :9000"), "go"),
            Case("3 Spring Boot", mapOf("pom.xml" to "<artifactId>spring-boot-starter-actuator</artifactId>"), "spring-boot", selected = false) {
                assertEquals(listOf("Liftgate can't build Java with Railpack yet. Add a Dockerfile."), it.warnings)
                assertEquals("/actuator/health", it.healthHint)
            },
            Case("4 Rust", mapOf("Cargo.toml" to "[package]\nname = \"api\"", "src/main.rs" to "TcpListener::bind(\"0.0.0.0:8000\")"), "rust", spec = { copy(port = 8000) }) {
                assertEquals("./bin/api", it.defaults.start)
            },
            Case("5 Rails", mapOf("Gemfile" to "gem \"rails\", \"~> 8.0\"", "config/routes.rb" to "get \"up\" => \"rails/health#show\""), "rails") { assertEquals("/up", it.healthHint) },
            Case("5 Rack", mapOf("Gemfile" to "gem \"sinatra\"", "config.ru" to "run App"), "rack"),
            Case("6 Phoenix", mapOf("mix.exs" to "{:phoenix, \"~> 1.7\"}"), "phoenix"),
            Case("7 FastAPI", mapOf("requirements.txt" to "fastapi==0.142.2\nuvicorn"), "fastapi") {
                assertEquals("uvicorn main:app --host 0.0.0.0 --port \${PORT:-8000}", it.defaults.start)
            },
            Case("7 Django", mapOf("manage.py" to "", "requirements.txt" to "Django>=5", "mysite/wsgi.py" to ""), "django") {
                assertEquals("python manage.py migrate && gunicorn --bind 0.0.0.0:\${PORT:-8000} mysite.wsgi:application", it.defaults.start)
            },
            Case("7 Flask without gunicorn", mapOf("requirements.txt" to "flask-cors\nFlask"), "flask") { assertEquals(listOf("Set a start command"), it.warnings) },
            Case("7 Python bot", mapOf("requirements.txt" to "discord.py==2.4", "bot.py" to ""), "python", WORKER),
            Case("8 Deno", mapOf("deno.json" to "{}"), "deno"),
            Case("9 .NET", mapOf("Shop.csproj" to "<Project/>"), "dotnet") { assertEquals(listOf("Untested on Liftgate"), it.warnings) },
            Case("11 Gleam", mapOf("gleam.toml" to "name = \"shop\""), "gleam"),
            Case("11 C++", mapOf("CMakeLists.txt" to "project(shop)", "index.html" to ""), "cpp"),
            Case("12 Static site", mapOf("index.html" to "<h1>shop</h1>"), "static", STATIC) { assertEquals("Caddy", it.defaults.start) },
            Case("13 Shell", mapOf("start.sh" to "echo hi"), "shell") { assertEquals("sh start.sh", it.defaults.start) },
            Case("14 nothing", mapOf("README.md" to "# shop"), null),
            Case("N1 Next.js", mapOf("package.json" to pkg(""""next":"^16.3.6""""), "pnpm-lock.yaml" to ""), "next") {
                assertEquals("From package.json (next 16.3.6) and pnpm-lock.yaml", it.evidence)
                assertEquals(DetectedService.Defaults("pnpm run build", "pnpm run start"), it.defaults)
                assertEquals("pnpm", it.packageManager)
            },
            Case("N1 Next.js export", mapOf("package.json" to pkg(""""next":"16"""")), "next", STATIC).let { case ->
                case.copy(files = case.files + ("next.config.mjs" to "export default { output: 'export' }"))
            },
            Case("N2 Nuxt", mapOf("package.json" to pkg(""""nuxt":"4"""")), "nuxt") { assertEquals("node .output/server/index.mjs", it.defaults.start) },
            Case("N3 Remix", mapOf("package.json" to pkg(""""@remix-run/dev":"2","vite":"6"""")), "remix"),
            Case("N4 React Router SPA", mapOf("package.json" to pkg(""""@react-router/dev":"7""""), "react-router.config.ts" to "export default { ssr: false }"), "react-router", STATIC),
            Case("N5 TanStack Start", mapOf("package.json" to pkg(""""@tanstack/react-start":"1","vite":"6"""")), "tanstack-start"),
            Case(
                "N6 SvelteKit adapter-node",
                mapOf("package.json" to pkg(""""@sveltejs/kit":"2","@sveltejs/adapter-node":"5","vite":"8"""", """"build":"vite build""""), "svelte.config.js" to ""),
                "sveltekit",
                spec = { copy(startCommand = "node build") },
            ),
            Case("N6 SvelteKit adapter-static", mapOf("package.json" to pkg(""""@sveltejs/kit":"2","@sveltejs/adapter-static":"3"""")), "sveltekit", STATIC),
            Case("N6 SvelteKit adapter-auto", mapOf("package.json" to pkg(""""@sveltejs/kit":"2","@sveltejs/adapter-auto":"3"""")), "sveltekit") {
                assertEquals(listOf("Choose adapter-node or adapter-static"), it.warnings)
            },
            Case("N7 Astro", mapOf("package.json" to pkg(""""astro":"5"""")), "astro", STATIC),
            Case("N7 Astro with the node adapter", mapOf("package.json" to pkg(""""astro":"5","@astrojs/node":"9"""")), "astro"),
            Case("N8 Angular", mapOf("package.json" to pkg(""""@angular/cli":"20"""")), "angular", STATIC),
            Case("N9 Vite", mapOf("package.json" to pkg(""""vite":"8"""")), "vite", STATIC),
            Case("N10 Create React App", mapOf("package.json" to pkg(""""react-scripts":"5"""")), "create-react-app", STATIC),
            Case("N11 Vue CLI", mapOf("package.json" to pkg(""""@vue/cli-service":"5"""")), "vue") { assertEquals(listOf("Add a start script or a Dockerfile"), it.warnings) },
            Case("N12 NestJS", mapOf("package.json" to pkg(""""@nestjs/core":"11","express":"5"""", """"build":"nest build","start":"nest start","start:prod":"node dist/main"""")), "nestjs") {
                assertEquals("npm run start:prod", it.defaults.start)
            },
            Case("N13 Express with a literal port", mapOf("package.json" to pkg(""""express":"5""""), "src/index.js" to "app.listen(3001)"), "express", spec = { copy(port = 3001) }),
            Case("N14 discord.js", mapOf("package.json" to pkg(""""discord.js":"14""""), "yarn.lock" to ""), "discord-js", WORKER) { assertEquals("yarn run start", it.defaults.start) },
            Case("N15 Node.js", mapOf("package.json" to """{"name":"shop"}"""), "node") { assertEquals(listOf("Set a start command"), it.warnings) },
            Case("N15 Bun", mapOf("package.json" to pkg(), "bun.lock" to ""), "bun"),
            Case("Procfile web", mapOf("Procfile" to "web: node server.js", "package.json" to pkg()), "node") {
                assertEquals("node server.js", it.defaults.start)
                assertEquals("From package.json and Procfile", it.evidence)
            },
            Case(
                "railway.json",
                mapOf("package.json" to pkg(), "railway.json" to """{"deploy":{"startCommand":"node api.js","healthcheckPath":"/ready"}}"""),
                "node",
                spec = { copy(startCommand = "node api.js", healthCheckPath = "/ready") },
            ),
            Case("railway.toml", mapOf("package.json" to pkg(), "railway.toml" to "[deploy]\ncronSchedule = \"*/5 * * * *\"\nstartCommand = \"node job.js\""), "node", CRON, spec = {
                copy(cronSchedule = "*/5 * * * *", startCommand = "node job.js")
            }),
            Case(
                "fly.toml",
                mapOf("package.json" to pkg(), "fly.toml" to "[http_service]\n  internal_port = 3000\n\n[[http_service.checks]]\n  method = \"GET\"\n  path = \"/health\"\n\n[env]\n  path = \"/other\""),
                "node",
                spec = { copy(port = 3000, healthCheckPath = "/health") },
            ),
            Case("fly.toml with a port out of range", mapOf("package.json" to pkg(), "fly.toml" to "[http_service]\n  internal_port = 99999999999"), "node"),
            Case(
                "app.json",
                mapOf("package.json" to pkg(), "app.json" to """{"env":{"SECRET_KEY_BASE":{"description":"Signs cookies","generator":"secret"},"API_URL":{"required":true},"LOG_LEVEL":"info"}}"""),
                "node",
            ) {
                assertEquals(
                    listOf(
                        DetectedVariable("SECRET_KEY_BASE", "Signs cookies", "app.json", required = false, secretHint = true),
                        DetectedVariable("API_URL", null, "app.json", required = true, secretHint = false),
                        DetectedVariable("LOG_LEVEL", null, "app.json", required = false, secretHint = false),
                    ),
                    it.variables,
                )
            },
        )
    }

    private fun detect(files: Map<String, String>, truncated: Boolean = false): Detection {
        val paths = files.keys.sorted()
        return Detector.detect("acme/shop", paths, Detector.wanted(paths).associateWith { files.getValue(it) }, truncated)
    }

    @ParameterizedTest
    @MethodSource("rows")
    fun `each rule row names the framework, kind, builder and spec`(case: Case) {
        val service = detect(case.files).services.first()
        assertEquals(case.framework, service.framework?.id)
        assertEquals(case.builder, service.builder)
        assertEquals(case.selected, service.selected)
        assertEquals(case.spec(ServiceSpec("shop", "shop", case.kind, framework = case.framework)), service.spec)
        case.check(service)
    }

    @Test
    fun `a Procfile worker line adds a worker that starts unchecked next to a web line`() {
        val services = detect(mapOf("package.json" to pkg(""""express":"5""""), "Procfile" to "web: node web.js\nworker: node jobs.js\nrelease: node migrate.js")).services
        assertEquals(listOf("shop" to WEB, "worker" to WORKER), services.map { it.spec.name to it.spec.kind })
        assertEquals("node jobs.js", services[1].spec.startCommand)
        assertEquals(listOf(true, false), services.map { it.selected })
    }

    @Test
    fun `providers follow Railpack's order and a Dockerfile wins with the manifest framework as its label`() {
        val vite = "package.json" to pkg(""""vite":"8"""")
        assertEquals("laravel", detect(mapOf("composer.json" to "{}", "artisan" to "", vite)).services.single().framework?.id)
        assertEquals("rails", detect(mapOf("Gemfile" to "gem 'rails'", vite)).services.single().framework?.id)
        assertEquals("django", detect(mapOf("manage.py" to "", "requirements.txt" to "django", vite)).services.single().framework?.id)
        assertEquals("go", detect(mapOf("go.mod" to "module shop", vite)).services.single().framework?.id)
        val docker = detect(mapOf("Dockerfile" to "FROM node:24\nEXPOSE 3000", "package.json" to pkg(""""next":"16""""))).services.single()
        assertEquals(Triple("dockerfile", "next", "Next.js"), Triple(docker.builder, docker.framework?.id, docker.framework?.name))
    }

    @Test
    fun `a pnpm workspace yields its apps built from the root with filter commands and drops tooling packages`() {
        val detection = detect(
            mapOf(
                "package.json" to """{"private":true,"packageManager":"pnpm@10.34.6"}""",
                "pnpm-workspace.yaml" to "packages:\n  - 'apps/*'\n  - \"packages/*\"\nonlyBuiltDependencies:\n  - esbuild",
                "pnpm-lock.yaml" to "",
                "apps/web/package.json" to """{"name":"@acme/web","scripts":{"build":"next build","start":"next start"},"dependencies":{"next":"16","@acme/ui":"workspace:*"}}""",
                "apps/api/package.json" to """{"name":"api","scripts":{"build":"nest build","start":"nest start"},"dependencies":{"@nestjs/core":"11"}}""",
                "apps/docs/package.json" to """{"name":"docs","scripts":{"build":"astro build","dev":"astro dev"},"dependencies":{"astro":"5"}}""",
                "packages/ui/package.json" to """{"name":"@acme/ui","scripts":{"build":"tsc"},"dependencies":{"react":"19"}}""",
                "packages/eslint-config/package.json" to """{"name":"@acme/eslint-config","scripts":{"build":"tsc"}}""",
                "services/billing/Dockerfile" to "FROM golang:1.25\nEXPOSE 9000",
                "services/billing/go.mod" to "module billing",
            ),
        )
        val (api, docs, web, billing) = detection.services
        assertEquals(listOf("api", "docs", "web", "billing"), detection.services.map { it.spec.name })
        assertEquals(listOf("/", "/", "/", "services/billing"), detection.services.map { it.spec.rootDir })
        assertEquals(ServiceSpec("web", "web", WEB, buildCommand = "pnpm --filter @acme/web build", startCommand = "pnpm --filter @acme/web start", framework = "next",
            watchPaths = listOf("apps/web/**", "packages/ui/**", "pnpm-lock.yaml", "package.json")), web.spec)
        assertEquals("pnpm --filter api build" to "pnpm --filter api start", api.spec.buildCommand to api.spec.startCommand)
        assertEquals(Triple("astro", STATIC, null), Triple(docs.framework?.id, docs.spec.kind, docs.spec.startCommand))
        assertEquals(Triple("dockerfile", emptyList(), 9000), Triple(billing.builder, billing.spec.watchPaths, billing.spec.port))
        assertTrue(detection.services.all { it.selected })
        assertEquals(listOf("/", "apps/api", "apps/docs", "apps/web", "packages/eslint-config", "packages/ui", "services/billing"), detection.directories.map { it.path })
    }

    @Test
    fun `npm workspaces filter by directory and ask to check the first build`() {
        val service = detect(
            mapOf(
                "package.json" to """{"workspaces":["apps/*"]}""",
                "package-lock.json" to "",
                "apps/site/package.json" to """{"name":"site","scripts":{"build":"vite build","start":"node server.js"},"dependencies":{"express":"5"}}""",
            ),
        ).services.single()
        assertEquals("npm run build --workspace apps/site" to "npm run start --workspace apps/site", service.spec.buildCommand to service.spec.startCommand)
        assertEquals(listOf("Check the first build"), service.warnings)
    }

    @Test
    fun `liftgate's own tree selects the control plane and the dashboard and skips build fixtures`() {
        val root = File("..").canonicalFile
        val paths = root.walk().onEnter { it.name !in setOf(".git", "node_modules", "build", ".gradle", ".next", ".kotlin") }
            .filter { it.isFile }.map { it.relativeTo(root).invariantSeparatorsPath }.toList().sorted()
        val detection = Detector.detect("liftgate/liftgate", paths, Detector.wanted(paths).associateWith { File(root, it).readText() }, false)
        assertEquals(
            listOf(Triple("build-image", "dockerfile", false), Triple("control-plane", "java", true), Triple("dashboard", "next", true)),
            detection.services.map { Triple(it.spec.rootDir, it.framework?.id, it.selected) }.sortedBy { it.first },
        )
        assertTrue(detection.directories.none { it.path.startsWith("build-image/test") })
    }

    @Test
    fun `each build-image fixture matches what railpack reports in expected json`() {
        val fixtures = File("../build-image/test")
        val providers = mapOf("node" to "node", "next" to "node", "vite" to "node", "sveltekit" to "node", "fastapi" to "python")
        json.parseToJsonElement(File(fixtures, "expected.json").readText()).jsonObject.forEach { (name, expected) ->
            val (provider, framework) = listOf("provider", "framework").map { (expected.jsonObject[it] as? JsonPrimitive)?.contentOrNull }
            val dir = File(fixtures, name)
            val paths = dir.walk().filter { it.isFile }.map { it.relativeTo(dir).invariantSeparatorsPath }.toList().sorted()
            val service = Detector.detect("acme/$name", paths, Detector.wanted(paths).associateWith { File(dir, it).readText() }, false).services.single()
            if (provider == null) {
                assertEquals("dockerfile", service.builder, name)
            } else {
                assertEquals(provider, providers[service.framework?.id], name)
                if (framework != provider) assertEquals(framework, service.framework?.id, name)
            }
        }
    }

    @Test
    fun `example env files give names, descriptions and hints but never values`() {
        val detection = detect(
            mapOf(
                "package.json" to pkg(""""next":"16""""),
                ".env.example" to "# Stripe secret key\nSTRIPE_SECRET_KEY=sk_live_x\n\n# OPTIONAL=1\nDATABASE_URL=postgres://u:p@h/db\nexport PORT=3000\nNEXT_PUBLIC_SITE=https://example.com",
                ".env" to "STRIPE_SECRET_KEY=sk_live_real",
            ),
        )
        assertEquals(
            listOf(
                DetectedVariable("STRIPE_SECRET_KEY", "Stripe secret key", ".env.example", required = false, secretHint = false),
                DetectedVariable("DATABASE_URL", null, ".env.example", required = false, secretHint = true),
                DetectedVariable("NEXT_PUBLIC_SITE", null, ".env.example", required = false, secretHint = false),
            ),
            detection.services.single().variables,
        )
        val serialized = json.encodeToString(Detection.serializer(), detection)
        listOf("sk_live_x", "u:p@", "sk_live_real", "example.com").forEach { assertFalse(it in serialized, it) }
        assertEquals(listOf("acme/shop commits a .env file. Liftgate didn't read it; rotate any secrets it holds."), detection.warnings)
        assertEquals(listOf("package.json", ".env.example"), Detector.wanted(listOf(".env", ".env.example", ".env.local", ".env.production", "package.json")))
    }

    @Test
    fun `wanted reads at most 50 files, rows stop at 10, and a truncated tree is partial`() {
        val files = (1..40).flatMap { i -> listOf("apps/a$i/package.json" to pkg(""""next":"16""""), "apps/a$i/.env.example" to "A=1", "apps/a$i/Procfile" to "web: x") }.toMap()
        assertEquals(50, Detector.wanted(files.keys.sorted()).size)
        val detection = detect(files.filterKeys { it.endsWith("package.json") }, truncated = true)
        assertEquals(10, detection.services.size)
        assertEquals(41, detection.directories.size)
        assertTrue(detection.partial)
    }
}
