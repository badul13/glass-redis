plugins {
    application
}

group = "dev.badul13.glassredis"
version = "0.1.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass = "glassredis.Main"

    // 윈도우 기본값 MS949면 UTF-8 터미널에서 한글 깨짐
    applicationDefaultJvmArgs = listOf("-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
}

// 소스 인코딩 UTF-8 고정 - 플랫폼 기본값 MS949 영향 차단
tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
    }
}

// 대시보드 빌드 - PATH에 npm 있을 때만, 서버만 쓰면 Node 불필요
// dist는 jar 리소스 dashboard/ 로 복사
val dashboardDir = layout.projectDirectory.dir("dashboard")
val npmCommand = if (System.getProperty("os.name").startsWith("Windows")) "npm.cmd" else "npm"

val npmOnPath = System.getenv("PATH").orEmpty()
    .split(File.pathSeparator)
    .any { File(it, npmCommand).canExecute() }

val installDashboard = tasks.register<Exec>("installDashboard") {
    group = "dashboard"
    description = "대시보드 의존성 설치 - package-lock.json 변경 시에만"
    workingDir = dashboardDir.asFile
    commandLine(npmCommand, "ci")
    inputs.file(dashboardDir.file("package-lock.json"))
    // 출력 판정 - node_modules 전체 대신 npm이 설치 끝에 남기는 파일 하나
    outputs.file(dashboardDir.file("node_modules/.package-lock.json"))
    onlyIf { npmOnPath }
}

val buildDashboard = tasks.register<Exec>("buildDashboard") {
    group = "dashboard"
    description = "대시보드 빌드 - Node 없으면 생략"
    dependsOn(installDashboard)
    workingDir = dashboardDir.asFile
    commandLine(npmCommand, "run", "build")
    inputs.dir(dashboardDir.dir("src"))
    inputs.dir(dashboardDir.dir("public"))
    inputs.files(
        dashboardDir.file("index.html"),
        dashboardDir.file("package.json"),
        dashboardDir.file("vite.config.ts"),
        dashboardDir.file("tsconfig.json"),
        dashboardDir.file("tsconfig.app.json"),
        dashboardDir.file("tsconfig.node.json"),
    )
    outputs.dir(dashboardDir.dir("dist"))
    onlyIf { npmOnPath }
}

tasks.processResources {
    dependsOn(buildDashboard)
    from(dashboardDir.dir("dist")) {
        into("dashboard")
    }
}
