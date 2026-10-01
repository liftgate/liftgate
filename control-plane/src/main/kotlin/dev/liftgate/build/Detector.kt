package dev.liftgate.build

import dev.liftgate.http.envVarName
import dev.liftgate.http.json
import dev.liftgate.service.ServiceKind
import dev.liftgate.service.ServiceSpec
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * @author Dean
 * @date 10/1/2026
 */
object Detector {
    private const val MAX_WANTED = 50
    private const val MAX_SERVICES = 10
    private const val MAX_DIRECTORIES = 50
    private const val MAX_DEPTH = 3
    private const val MAX_DESCRIPTION = 120
    private const val MAX_WATCH_PATHS = 20
    private const val RAILPACK = "railpack"
    private const val DOCKERFILE = "dockerfile"
    private const val CADDY = "Caddy"
    private const val SET_START = "Set a start command"

    private val markers = setOf(
        "package.json", "requirements.txt", "pyproject.toml", "Pipfile", "go.mod", "Cargo.toml", "Gemfile", "composer.json", "pom.xml",
        "build.gradle", "build.gradle.kts", "deno.json", "deno.jsonc", "mix.exs", "Dockerfile", "index.html", "start.sh",
    )
    private val skipped = setOf("node_modules", "vendor", "dist", "build", "test", "tests", "__tests__", "testdata", "fixtures", "examples", "e2e", "docs")
    private val manifests = setOf(
        "package.json", "Dockerfile", "requirements.txt", "pyproject.toml", "Pipfile", "go.mod", "Cargo.toml", "Gemfile", "composer.json", "mix.exs",
        "pom.xml", "build.gradle", "build.gradle.kts",
    )
    private val configs = Regex("""Procfile|railway\.json|railway\.toml|fly\.toml|app\.json|(next|astro|react-router|remix)\.config\.[a-z]+""")
    private val envExample = Regex("""\.env\.(example|sample|template|dist)|example\.env|\.env\.[^/]+\.example""")
    private val entries = listOf("app", "index", "server", "src/index", "src/app", "src/server", "src/main").flatMap { base -> listOf("js", "mjs", "cjs", "ts", "mts").map { "$base.$it" } }
    private val heuristics = Regex("""main\.go|cmd/[^/]+/main\.go|src/main\.rs|config/routes\.rb|Gemfile\.lock|${entries.joinToString("|") { Regex.escape(it) }}""")
    private val goMains = Regex("""main\.go|cmd/[^/]+/main\.go""")
    private val committedEnv = setOf(".env", ".env.local", ".env.production")
    private val notApps = setOf("eslint", "prettier", "tsconfig", "config", "types", "ui", "test", "e2e")
    private val lockfiles = linkedMapOf("pnpm-lock.yaml" to "pnpm", "bun.lock" to "bun", "bun.lockb" to "bun", "yarn.lock" to "yarn", "package-lock.json" to "npm")
    private val httpServers = mapOf("express" to "Express", "fastify" to "Fastify", "hono" to "Hono", "koa" to "Koa", "elysia" to "Elysia", "h3" to "h3")
    private val bots = mapOf("discord.js" to "discord.js", "telegraf" to "Telegraf", "grammy" to "grammY", "bullmq" to "BullMQ")
    private val pythonHttp = listOf("django", "fastapi", "flask", "starlette", "aiohttp", "sanic", "quart", "python-fasthtml", "streamlit", "gunicorn", "uvicorn")
    private val pythonBots = listOf("discord.py", "py-cord", "nextcord", "python-telegram-bot", "aiogram")
    private val assignment = Regex("""(?:export\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*=(.*)""")
    private val credentialUrl = Regex("""^\s*["']?[A-Za-z][A-Za-z0-9+.-]*://[^\s/:@]+:[^\s/@]+@""")

    fun wanted(paths: List<String>): List<String> {
        val dirs = candidates(paths)
        fun tier(match: (String) -> Boolean) = dirs.flatMap { dir -> paths.filter { it.startsWith(prefix(dir)) && match(it.removePrefix(prefix(dir))) } }
        return (paths.filter { it == "pnpm-workspace.yaml" || it == "lerna.json" } + tier { it in manifests } + tier(configs::matches) + tier(envExample::matches) + tier(heuristics::matches))
            .distinct()
            .take(MAX_WANTED)
    }

    fun detect(repo: String, paths: List<String>, files: Map<String, String>, truncated: Boolean): Detection {
        val tree = Tree(paths, files)
        val dirs = candidates(paths)
        val workspace = tree.workspace(dirs)
        val rows = dirs.associateWith { tree.rows(repo, it, workspace) }
        val found = rows.values.flatten().ifEmpty { listOf(nothing(repo)) }.take(MAX_SERVICES)
        return Detection(
            ref = "",
            commit = null,
            partial = truncated,
            services = found.map { it.copy(selected = (found.size == 1 || it.selected) && (it.builder == DOCKERFILE || it.framework?.id !in setOf("java", "spring-boot"))) },
            directories = dirs.take(MAX_DIRECTORIES).map { Detection.Directory(it.ifEmpty { "/" }, rows[it]?.firstOrNull()?.framework?.id) },
            warnings = listOfNotNull("$repo commits a .env file. Liftgate didn't read it; rotate any secrets it holds.".takeIf { paths.any { name(it) in committedEnv } }),
        )
    }

    private fun candidates(paths: Collection<String>): List<String> =
        (listOf("") + paths.filter { name(it) in markers || name(it).endsWith(".csproj") }.map(::parent).filter { dir ->
            dir.split('/').let { segments -> segments.size <= MAX_DEPTH && segments.withIndex().none { (i, segment) -> segment.startsWith(".") || (segment in skipped && !(i == 1 && segments[0] == "apps")) } }
        }).distinct().sortedWith(compareBy({ it.isNotEmpty() }, { !it.startsWith("apps/") }, { it }))

    private fun Tree.rows(repo: String, dir: String, workspace: Workspace?): List<DetectedService> {
        val member = workspace?.members?.get(dir)
        val row = (if (member != null) member(dir, member, workspace) else match(dir, dir.isEmpty() && workspace != null)) ?: return emptyList()
        val name = if (dir.isEmpty()) repo.substringAfterLast('/') else (obj(dir, "package.json")?.str("name") ?: dir).substringAfterLast('/')
        val rootDir = if (member != null || dir.isEmpty()) "/" else dir
        return overlay(dir, row.copy(spec = row.spec.copy(slug = slug(name), name = name, rootDir = rootDir), variables = variables(dir)))
    }

    private fun Tree.match(dir: String, workspaceRoot: Boolean): DetectedService? {
        val manifest = php(dir) ?: go(dir) ?: java(dir) ?: rust(dir) ?: ruby(dir) ?: elixir(dir) ?: python(dir) ?: deno(dir) ?: dotnet(dir)
            ?: (if (workspaceRoot) null else node(dir, packageManager(dir))) ?: other(dir)
        return dockerfile(dir, railway(dir, "dockerfilePath") ?: "Dockerfile", manifest) ?: manifest
    }

    private fun Tree.member(dir: String, pkg: JsonObject, workspace: Workspace): DetectedService? {
        val name = pkg.str("name") ?: dir.substringAfterLast('/')
        if ((dir.split('/') + name).flatMap { it.split('-', '_', '.', '@', '/') }.any { it in notApps }) return null
        val scripts = pkg.fields("scripts")
        val row = node(dir, workspace.pm) ?: return null
        if (("build" !in scripts && "start" !in scripts) || (!row.selected && "start" !in scripts && "dev" !in scripts)) return null
        fun run(script: String) = when (workspace.pm) {
            "pnpm" -> "pnpm --filter $name $script"
            "yarn" -> "yarn workspace $name $script"
            "bun" -> "bun run --filter $name $script"
            else -> "npm run $script --workspace $dir"
        }.takeIf { script in scripts }
        val watched = dependencies(pkg).filter { (dep, version) -> version.startsWith("workspace:") || dep in workspace.dirs }.keys.mapNotNull { workspace.dirs[it] }
        val warnings = listOfNotNull(SET_START.takeIf { run("start") == null && row.spec.kind != ServiceKind.STATIC }, "Check the first build".takeIf { workspace.pm != "pnpm" })
        return row.copy(
            spec = row.spec.copy(
                buildCommand = run("build"),
                startCommand = run("start"),
                watchPaths = (listOf(dir) + watched).map { "$it/**" }.plus(listOfNotNull(workspace.lockfile, "package.json")).distinct().take(MAX_WATCH_PATHS),
            ),
            defaults = DetectedService.Defaults(run("build"), run("start")),
            warnings = (row.warnings + warnings).distinct(),
        )
    }

    private fun Tree.dockerfile(dir: String, file: String, label: DetectedService?): DetectedService? {
        if (!has(dir, file)) return null
        val text = text(dir, file).orEmpty().replace(Regex("""\\\r?\n"""), " ")
        val exposed = Regex("""(?im)^\s*EXPOSE\s+(\d+)""").findAll(text).mapNotNull { validPort(it.groupValues[1]) }.toSet()
        val port = exposed.singleOrNull() ?: Regex("""(?im)^\s*ENV\s+PORT[=\s]+["']?(\d+)""").find(text)?.groupValues?.get(1)?.let(::validPort)
        val health = Regex("""(?im)^\s*HEALTHCHECK\b.*\b(?:curl|wget)\b.*?https?://(?:localhost|127\.0\.0\.1)(?::\d+)?(/[^\s"'|&;)]*)""").find(text)?.groupValues?.get(1)
        val development = file.endsWith(".dev") || Regex("""(?im)^\s*(?:CMD|ENTRYPOINT)\b.*(?:npm run dev|--reload|nodemon)""").containsMatchIn(text)
        val framework = label?.framework ?: fw(DOCKERFILE, "Dockerfile")
        val kind = if (label?.spec?.kind == ServiceKind.WORKER) ServiceKind.WORKER else ServiceKind.WEB
        return DetectedService(
            selected = exposed.isNotEmpty() || label?.selected == true,
            framework = framework,
            packageManager = label?.packageManager,
            builder = DOCKERFILE,
            spec = ServiceSpec("", "", kind, dockerfilePath = file, port = port, healthCheckPath = health?.takeIf { kind.servesHttp || port != null }, framework = framework.id),
            defaults = DetectedService.Defaults(null, null),
            evidence = from(file),
            warnings = listOfNotNull("Looks like a development Dockerfile".takeIf { development }),
            healthHint = null,
            variables = emptyList(),
        )
    }

    private fun Tree.php(dir: String): DetectedService? {
        val file = listOf("composer.json", "index.php").firstOrNull { has(dir, it) } ?: return null
        val laravel = has(dir, "artisan")
        return row(if (laravel) fw("laravel", "Laravel") else fw("php", "PHP"), from(file, "artisan".takeIf { laravel }), start = "/start-container.sh", healthHint = "/up".takeIf { laravel }, specific = laravel)
    }

    private fun Tree.go(dir: String): DetectedService? {
        val file = listOf("go.mod", "go.work", "main.go").firstOrNull { has(dir, it) } ?: return null
        val gin = text(dir, "go.mod")?.contains("gin-gonic/gin") == true
        val sources = under(dir, goMains).mapNotNull { text(dir, it) }
        val port = sources.takeIf { it.none { source -> "os.Getenv(\"PORT\")" in source } }
            ?.firstNotNullOfOrNull { Regex("""(?:ListenAndServe|Run|Listen)\(\s*"[^"]*:(\d{2,5})"""").find(it)?.groupValues?.get(1)?.let(::validPort) }
        return row(if (gin) fw("gin", "Gin") else fw("go", "Go"), from(file), port = port, start = "./out", specific = gin)
    }

    private fun Tree.java(dir: String): DetectedService? {
        val file = listOf("pom.xml", "build.gradle", "build.gradle.kts").firstOrNull { has(dir, it) } ?: return null
        val text = text(dir, file).orEmpty()
        return row(
            if ("spring-boot" in text) fw("spring-boot", "Spring Boot") else fw("java", "Java"),
            from(file),
            warnings = listOf("Liftgate can't build Java with Railpack yet. Add a Dockerfile."),
            healthHint = "/actuator/health".takeIf { "spring-boot-starter-actuator" in text },
            specific = false,
        )
    }

    private fun Tree.rust(dir: String): DetectedService? {
        if (!has(dir, "Cargo.toml")) return null
        val crate = Regex("""(?m)^\s*name\s*=\s*"([^"]+)"""").find(text(dir, "Cargo.toml").orEmpty())?.groupValues?.get(1)
        val port = text(dir, "src/main.rs")?.takeIf { "env::var(\"PORT\"" !in it }
            ?.let { Regex(""""[^"\s]*:(\d{2,5})"|]\s*,\s*(\d{2,5})\s*\)""").find(it)?.groupValues?.drop(1)?.firstOrNull(String::isNotEmpty)?.let(::validPort) }
        return row(fw("rust", "Rust"), from("Cargo.toml"), port = port, start = crate?.let { "./bin/$it" }, specific = false)
    }

    private fun Tree.ruby(dir: String): DetectedService? {
        if (!has(dir, "Gemfile")) return null
        val rails = Regex("""(?m)^\s*gem\s+["']rails["']""").containsMatchIn(text(dir, "Gemfile").orEmpty()) || Regex("""(?m)^ {4}rails \(""").containsMatchIn(text(dir, "Gemfile.lock").orEmpty())
        return when {
            rails -> row(
                fw("rails", "Rails"),
                from("Gemfile (rails)"),
                start = "bundle exec rails server -b 0.0.0.0 -p \${PORT:-3000}",
                healthHint = "/up".takeIf { text(dir, "config/routes.rb")?.contains("rails/health#show") == true },
            )
            has(dir, "config.ru") -> row(fw("rack", "Rack"), from("Gemfile", "config.ru"), start = "bundle exec rackup config.ru -o 0.0.0.0 -p \${PORT:-3000}")
            else -> row(fw("ruby", "Ruby"), from("Gemfile"), specific = false)
        }
    }

    private fun Tree.elixir(dir: String): DetectedService? {
        if (!has(dir, "mix.exs")) return null
        val phoenix = Regex(""":phoenix\b""").containsMatchIn(text(dir, "mix.exs").orEmpty())
        return row(if (phoenix) fw("phoenix", "Phoenix") else fw("elixir", "Elixir"), from("mix.exs"), specific = phoenix)
    }

    private fun Tree.python(dir: String): DetectedService? {
        val sources = listOf("requirements.txt", "pyproject.toml", "Pipfile", "uv.lock").filter { has(dir, it) }
        val file = sources.firstOrNull() ?: listOf("main.py", "app.py", "start.py", "bot.py", "hello.py", "server.py").firstOrNull { has(dir, it) } ?: return null
        val text = sources.mapNotNull { text(dir, it) }.joinToString("\n")
        fun uses(dependency: String) = Regex("""(?i)(?<![\w.-])${Regex.escape(dependency)}(?![\w-])""").containsMatchIn(text)
        val wsgi = under(dir, Regex("""[^/]+/wsgi\.py""")).firstOrNull()
        return when {
            has(dir, "manage.py") || uses("django") -> row(
                fw("django", "Django"),
                from(file, "manage.py".takeIf { has(dir, it) }),
                start = wsgi?.let { "python manage.py migrate && gunicorn --bind 0.0.0.0:\${PORT:-8000} ${it.substringBefore('/')}.wsgi:application" },
                warnings = listOfNotNull(SET_START.takeIf { wsgi == null }),
            )
            uses("fastapi") -> row(fw("fastapi", "FastAPI"), from("$file (fastapi)"), start = "uvicorn main:app --host 0.0.0.0 --port \${PORT:-8000}")
            uses("python-fasthtml") -> row(fw("fasthtml", "FastHTML"), from("$file (python-fasthtml)"))
            uses("flask") -> row(
                fw("flask", "Flask"),
                from("$file (flask)"),
                start = "gunicorn --bind 0.0.0.0:\${PORT:-8000} main:app".takeIf { uses("gunicorn") },
                warnings = listOfNotNull(SET_START.takeIf { !uses("gunicorn") }),
            )
            pythonHttp.none(::uses) && (pythonBots.any(::uses) || has(dir, "bot.py") || has(dir, "worker.py")) ->
                row(fw("python", "Python"), from(file, "bot.py".takeIf { has(dir, it) } ?: "worker.py".takeIf { has(dir, it) }), ServiceKind.WORKER)
            else -> row(fw("python", "Python"), from(file), specific = false)
        }
    }

    private fun Tree.deno(dir: String): DetectedService? = listOf("deno.json", "deno.jsonc").firstOrNull { has(dir, it) }
        ?.let { row(fw("deno", "Deno"), from(it), warnings = listOf("Your app must listen on \$PORT"), specific = false) }

    private fun Tree.dotnet(dir: String): DetectedService? = children(dir).firstOrNull { it.endsWith(".csproj") || it.endsWith(".sln") }
        ?.let { row(fw("dotnet", ".NET"), from(it), warnings = listOf("Untested on Liftgate"), specific = false) }

    private fun Tree.other(dir: String): DetectedService? = when {
        has(dir, "gleam.toml") -> row(fw("gleam", "Gleam"), from("gleam.toml"), specific = false)
        has(dir, "CMakeLists.txt") -> row(fw("cpp", "C++"), from("CMakeLists.txt"), specific = false)
        has(dir, "Staticfile") || has(dir, "index.html") || under(dir, Regex("public/.+")).isNotEmpty() ->
            row(fw("static", "Static site"), from(listOf("Staticfile", "index.html").firstOrNull { has(dir, it) } ?: "public/"), ServiceKind.STATIC, start = CADDY, specific = false)
        has(dir, "start.sh") -> row(fw("shell", "Shell"), from("start.sh"), start = "sh start.sh", specific = false)
        else -> null
    }

    private fun Tree.node(dir: String, pm: String): DetectedService? {
        val pkg = obj(dir, "package.json") ?: return null
        val deps = dependencies(pkg)
        val scripts = pkg.fields("scripts")
        fun run(script: String) = "$pm run $script".takeIf { script in scripts }
        fun config(name: String) = children(dir).firstOrNull { it.startsWith("$name.config.") }?.let { text(dir, it).orEmpty() }
        fun dep(vararg names: String) = names.firstOrNull { it in deps }
        fun evidence(dependency: String?) = from("package.json${dependency?.let { " ($it ${deps[it]?.trimStart('^', '~', '>', '=', 'v')})" }.orEmpty()}", lockfile(dir))
        fun web(framework: DetectedService.Framework, dependency: String?, start: String? = run("start"), kind: ServiceKind = ServiceKind.WEB, warnings: List<String> = emptyList()) =
            row(framework, evidence(dependency), kind, start = start, build = run("build"), warnings = warnings, packageManager = pm)
        fun spa(framework: DetectedService.Framework, dependency: String?, server: Boolean, start: String? = run("start")) =
            web(framework, dependency, if (server) start else CADDY, if (server) ServiceKind.WEB else ServiceKind.STATIC)
        val adapter = dep("@sveltejs/adapter-static", "@sveltejs/adapter-node")
        val http = dep(*httpServers.keys.toTypedArray())
        val bot = dep(*bots.keys.toTypedArray())
        return when {
            "next" in deps -> spa(fw("next", "Next.js"), "next", config("next")?.contains(Regex("""output\s*:\s*["']export["']""")) != true)
            dep("nuxt", "nuxt3") != null -> web(fw("nuxt", "Nuxt"), dep("nuxt", "nuxt3"), "node .output/server/index.mjs")
            "@remix-run/dev" in deps || config("remix") != null -> web(fw("remix", "Remix"), dep("@remix-run/dev"))
            "@react-router/dev" in deps || config("react-router") != null ->
                spa(fw("react-router", "React Router"), dep("@react-router/dev"), config("react-router")?.contains(Regex("""ssr\s*:\s*false""")) != true)
            dep("@tanstack/react-start", "@tanstack/solid-start") != null -> web(fw("tanstack-start", "TanStack Start"), dep("@tanstack/react-start", "@tanstack/solid-start"), null)
            "@sveltejs/kit" in deps -> when (adapter) {
                "@sveltejs/adapter-static" -> spa(fw("sveltekit", "SvelteKit"), "@sveltejs/kit", false)
                "@sveltejs/adapter-node" -> web(fw("sveltekit", "SvelteKit"), "@sveltejs/kit", run("start") ?: "node build")
                    .let { it.copy(spec = it.spec.copy(startCommand = "node build".takeIf { "start" !in scripts })) }
                else -> web(fw("sveltekit", "SvelteKit"), "@sveltejs/kit", warnings = listOf("Choose adapter-node or adapter-static"))
            }
            "astro" in deps -> spa(fw("astro", "Astro"), "astro", "@astrojs/node" in deps || config("astro")?.contains(Regex("""output\s*:\s*["']server["']""")) == true, null)
            "@angular/cli" in deps -> spa(fw("angular", "Angular"), "@angular/cli", "@angular/ssr" in deps, null)
            "vite" in deps -> spa(fw("vite", "Vite"), "vite", false)
            "react-scripts" in deps -> spa(fw("create-react-app", "Create React App"), "react-scripts", false)
            "@vue/cli-service" in deps -> web(fw("vue", "Vue CLI"), "@vue/cli-service", null, warnings = listOf("Add a start script or a Dockerfile"))
            "@nestjs/core" in deps -> web(fw("nestjs", "NestJS"), "@nestjs/core", run("start:prod") ?: run("start"))
            http != null -> web(fw(http, httpServers.getValue(http)), http).let { row ->
                val port = entries.firstNotNullOfOrNull { text(dir, it) }?.takeIf { "process.env.PORT" !in it }
                    ?.let { Regex("""\.listen\(\s*(\d{2,5})\b""").find(it)?.groupValues?.get(1)?.let(::validPort) }
                row.copy(spec = row.spec.copy(port = port))
            }
            bot != null -> web(fw(bot.replace('.', '-'), bots.getValue(bot)), bot, kind = ServiceKind.WORKER)
            else -> web(
                if (pm == "bun") fw("bun", "Bun") else fw("node", "Node.js"),
                null,
                warnings = listOfNotNull(SET_START.takeIf { "start" !in scripts && pkg.str("main") == null && children(dir).none { it.startsWith("index.") } }),
            ).copy(selected = false)
        }
    }

    private fun Tree.overlay(dir: String, row: DetectedService): List<DetectedService> {
        val procfile = text(dir, "Procfile")?.lines()?.mapNotNull { Regex("""^([A-Za-z0-9_-]+):\s*(.+)$""").find(it.trim())?.destructured?.let { (type, command) -> type to command.trim() } }.orEmpty()
        val web = procfile.firstOrNull { it.first == "web" }?.second
        val fly = text(dir, "fly.toml")
        val cron = railway(dir, "cronSchedule")
        val kind = if (cron != null) ServiceKind.CRON else row.spec.kind
        val spec = row.spec.copy(
            kind = kind,
            cronSchedule = cron,
            startCommand = railway(dir, "startCommand") ?: row.spec.startCommand,
            port = fly?.let { Regex("""(?m)^\s*internal_port\s*=\s*(\d+)""").find(it)?.groupValues?.get(1)?.let(::validPort) } ?: row.spec.port,
            healthCheckPath = railway(dir, "healthcheckPath")
                ?: fly?.let { Regex("""(?ms)^\s*\[\[http_service\.checks]]\s*$(.*?)(?=^\s*\[|\z)""").find(it)?.groupValues?.get(1) }
                    ?.let { Regex("""(?m)^\s*path\s*=\s*["'](/[^"']*)["']""").find(it)?.groupValues?.get(1) }
                ?: row.spec.healthCheckPath,
        ).let { if (kind == ServiceKind.CRON) it.copy(port = null, healthCheckPath = null) else it }
        val explicit = listOf("railway.json", "railway.toml", "fly.toml").filter { has(dir, it) } + listOfNotNull("Procfile".takeIf { web != null })
        val main = row.copy(
            selected = row.selected || explicit.isNotEmpty(),
            spec = spec,
            defaults = row.defaults.copy(start = web ?: row.defaults.start),
            evidence = row.evidence + explicit.joinToString("") { " and $it" },
        )
        return listOf(main) + procfile.filter { it.first != "web" && it.first != "release" }.map { (type, command) ->
            main.copy(
                selected = web == null,
                spec = spec.copy(slug = slug(type), name = type, kind = ServiceKind.WORKER, startCommand = command, port = null, healthCheckPath = null, cronSchedule = null),
                defaults = row.defaults.copy(start = command),
                evidence = "From Procfile ($type)",
            )
        }
    }

    private fun Tree.variables(dir: String): List<DetectedVariable> =
        listOf(dir, "").distinct().flatMap { d -> children(d).filter(envExample::matches).flatMap { dotenv(path(d, it), text(d, it)) } + appJson(d) }
            .filter { it.name != "PORT" }
            .groupBy { it.name }
            .map { (_, same) -> same.first().copy(description = same.firstNotNullOfOrNull { it.description }, required = same.any { it.required }, secretHint = same.any { it.secretHint }) }

    private fun dotenv(source: String, text: String?): List<DetectedVariable> {
        var comment: String? = null
        return text.orEmpty().lines().mapNotNull { line ->
            val trimmed = line.trim()
            val above = comment
            comment = trimmed.takeIf { it.startsWith("#") }?.trimStart('#')?.trim()?.takeUnless { it.isEmpty() || assignment.matches(it) }?.take(MAX_DESCRIPTION)
            assignment.matchEntire(trimmed)?.let { DetectedVariable(it.groupValues[1], above, source, required = false, secretHint = credentialUrl.containsMatchIn(it.groupValues[2])) }
        }
    }

    private fun Tree.appJson(dir: String): List<DetectedVariable> = obj(dir, "app.json")?.get("env").let { it as? JsonObject }.orEmpty()
        .filterKeys(envVarName::matches)
        .map { (name, value) ->
            val entry = value as? JsonObject
            DetectedVariable(
                name,
                entry?.str("description")?.take(MAX_DESCRIPTION),
                path(dir, "app.json"),
                required = entry != null && ((entry["required"] as? JsonPrimitive)?.booleanOrNull ?: (entry["value"] == null && entry["generator"] == null)),
                secretHint = entry?.str("generator") == "secret",
            )
        }

    private fun Tree.workspace(dirs: List<String>): Workspace? {
        val root = obj("", "package.json")?.get("workspaces")
        val globs = ((root as? JsonObject)?.get("packages") ?: root).strings() + pnpmPackages(text("", "pnpm-workspace.yaml")) + obj("", "lerna.json")?.get("packages").strings()
        val (exclude, include) = globs.ifEmpty { if (has("", "turbo.json") || has("", "nx.json")) listOf("apps/*", "packages/*") else return null }
            .partition { it.startsWith("!") }
            .let { (negated, positive) -> negated.map { glob(it.drop(1)) } to positive.map(::glob) }
        val members = dirs.filter { dir -> dir.isNotEmpty() && include.any { it.matches(dir) } && exclude.none { it.matches(dir) } }
            .mapNotNull { dir -> obj(dir, "package.json")?.let { dir to it } }
            .toMap()
        return Workspace(members, packageManager(""), lockfiles.keys.firstOrNull { has("", it) })
    }

    private fun pnpmPackages(yaml: String?): List<String> {
        var inside = false
        return yaml.orEmpty().lines().mapNotNull { line ->
            if (line.isNotBlank() && !line.first().isWhitespace() && !line.startsWith("-")) inside = line.startsWith("packages:")
            if (inside) Regex("""^\s*-\s*["']?([^"'#\s]+)""").find(line)?.groupValues?.get(1) else null
        }
    }

    private fun glob(pattern: String) = Regex(pattern.trim().removePrefix("./").trimEnd('/').split("**").joinToString(".*") { part -> part.split('*').joinToString("[^/]*") { Regex.escape(it) } })

    private fun Tree.packageManager(dir: String): String = listOf(dir, "").firstNotNullOfOrNull { obj(it, "package.json")?.str("packageManager")?.substringBefore('@') }
        ?: listOf(dir, "").firstNotNullOfOrNull { d -> lockfiles.entries.firstOrNull { has(d, it.key) }?.value }
        ?: "npm"

    private fun Tree.lockfile(dir: String) = listOf(dir, "").distinct().firstNotNullOfOrNull { d -> lockfiles.keys.firstOrNull { has(d, it) } }

    private fun Tree.railway(dir: String, key: String): String? =
        obj(dir, "railway.json")?.let { (it["deploy"] as? JsonObject)?.str(key) ?: (it["build"] as? JsonObject)?.str(key) }
            ?: text(dir, "railway.toml")?.let { Regex("""(?m)^\s*$key\s*=\s*["']([^"']*)["']""").find(it)?.groupValues?.get(1) }

    private fun nothing(repo: String) = row(null, "No app found here", specific = false)
        .let { it.copy(spec = it.spec.copy(slug = slug(repo.substringAfterLast('/')), name = repo.substringAfterLast('/'))) }

    private fun row(
        framework: DetectedService.Framework?,
        evidence: String,
        kind: ServiceKind = ServiceKind.WEB,
        start: String? = null,
        build: String? = null,
        port: Int? = null,
        healthHint: String? = null,
        warnings: List<String> = emptyList(),
        specific: Boolean = true,
        packageManager: String? = null,
    ) = DetectedService(
        selected = specific,
        framework = framework,
        packageManager = packageManager,
        builder = RAILPACK,
        spec = ServiceSpec("", "", kind, port = port, framework = framework?.id),
        defaults = DetectedService.Defaults(build, start),
        evidence = evidence,
        warnings = warnings,
        healthHint = healthHint,
        variables = emptyList(),
    )

    private fun fw(id: String, name: String) = DetectedService.Framework(id, name)

    private fun from(vararg parts: String?) = "From " + parts.filterNotNull().joinToString(" and ")

    private fun dependencies(pkg: JsonObject) = listOf("dependencies", "devDependencies").flatMap { key ->
        (pkg[key] as? JsonObject).orEmpty().map { (name, version) -> name to ((version as? JsonPrimitive)?.contentOrNull ?: "") }
    }.toMap()

    private fun validPort(digits: String) = digits.toIntOrNull()?.takeIf { it in 1..65535 }

    private fun slug(name: String) = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40).trimEnd('-')

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.fields(key: String) = (this[key] as? JsonObject)?.keys.orEmpty()

    private fun JsonElement?.strings() = (this as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()

    private fun parent(path: String) = path.substringBeforeLast('/', "")

    private fun name(path: String) = path.substringAfterLast('/')

    private fun prefix(dir: String) = if (dir.isEmpty()) "" else "$dir/"

    private fun path(dir: String, rel: String) = prefix(dir) + rel

    private class Tree(val paths: List<String>, val files: Map<String, String>) {
        private val set = paths.toSet()
        private val byParent = paths.groupBy { it.substringBeforeLast('/', "") }
        private val parsed = HashMap<String, JsonObject?>()

        fun has(dir: String, rel: String) = path(dir, rel) in set

        fun text(dir: String, rel: String) = files[path(dir, rel)]

        fun children(dir: String) = byParent[dir].orEmpty().map { it.substringAfterLast('/') }

        fun under(dir: String, rel: Regex) = paths.filter { it.startsWith(prefix(dir)) }.map { it.removePrefix(prefix(dir)) }.filter(rel::matches)

        fun obj(dir: String, rel: String) = parsed.getOrPut(path(dir, rel)) { text(dir, rel)?.let { runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() } }
    }

    private class Workspace(val members: Map<String, JsonObject>, val pm: String, val lockfile: String?) {
        val dirs = members.entries.mapNotNull { (dir, pkg) -> (pkg["name"] as? JsonPrimitive)?.contentOrNull?.let { it to dir } }.toMap()
    }
}
