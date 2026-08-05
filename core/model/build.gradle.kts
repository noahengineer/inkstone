plugins {
    id("inkstone.kmp-core")
}

// :core:model has no external dependencies on purpose. It is the module every
// other module points at, so anything added here is added everywhere — and it
// is the module the M0 acceptance check pokes with `import android.content.Context`.
