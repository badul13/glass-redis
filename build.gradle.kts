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

    // 콘솔 출력 인코딩을 UTF-8 로 고정한다.
    // 지정하지 않으면 JVM 이 플랫폼 기본값(윈도우 한국어 환경은 MS949)으로 인코딩해서
    // UTF-8 터미널에서 한글 메시지가 깨진다.
    applicationDefaultJvmArgs = listOf("-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
}

// 소스에 한글 주석이 들어간다. 플랫폼 기본 인코딩(윈도우 한국어 환경은 MS949)에 맡기면
// 환경마다 컴파일 결과가 달라지므로 UTF-8 로 못박는다.
tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
    }
}

// 대시보드(React)는 npm 이 빌드한다. Node 가 있으면 Gradle 이 알아서 돌리고, 없으면 건너뛴다 —
// 서버만 쓰는 사람에게 Node 를 요구하지 않으면서, 클론 후 ./gradlew run 한 번으로 화면까지 뜨게 하려는 것.
// 빌드 결과(dist)는 jar 리소스로 들어가고 DashboardServer 가 클래스패스에서 꺼내 내보낸다.
val dashboardDir = layout.projectDirectory.dir("dashboard")
val npmCommand = if (System.getProperty("os.name").startsWith("Windows")) "npm.cmd" else "npm"

val npmOnPath = System.getenv("PATH").orEmpty()
    .split(File.pathSeparator)
    .any { File(it, npmCommand).canExecute() }

val installDashboard = tasks.register<Exec>("installDashboard") {
    group = "dashboard"
    description = "대시보드 의존성을 설치한다. package-lock.json 이 바뀌었을 때만 다시 돈다."
    workingDir = dashboardDir.asFile
    commandLine(npmCommand, "ci")
    inputs.file(dashboardDir.file("package-lock.json"))
    // node_modules 전체를 출력으로 잡으면 up-to-date 검사가 수만 개 파일을 훑는다.
    // npm 이 설치를 마치며 남기는 이 파일 하나만 본다.
    outputs.file(dashboardDir.file("node_modules/.package-lock.json"))
    onlyIf { npmOnPath }
}

val buildDashboard = tasks.register<Exec>("buildDashboard") {
    group = "dashboard"
    description = "대시보드를 빌드한다. Node 가 없으면 건너뛴다."
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
