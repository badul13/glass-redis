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
