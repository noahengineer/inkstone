plugins {
    id("inkstone.kmp-core")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":core:model"))
        }
    }
}
