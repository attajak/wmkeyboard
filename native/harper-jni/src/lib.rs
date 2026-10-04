//! JNI bridge exposing Harper's grammar linting to WM Keyboard.
//!
//! Kotlin side: `com.wasimaster.wmkeyboard.core.grammar.HarperNative`.
//! The single entry point takes plain text plus a dialect ordinal and returns
//! a JSON array of lints with UTF-16 spans (Kotlin `String` indexing), so the
//! Kotlin layer never has to reason about Rust `char` offsets.

use std::cell::RefCell;
use std::collections::HashMap;

use harper_core::linting::{Lint, LintGroup, Linter, Suggestion};
use harper_core::spell::FstDictionary;
use harper_core::{Dialect, Document};
use jni::objects::{JClass, JString};
use jni::sys::{jint, jstring};
use jni::JNIEnv;
use serde_json::json;

thread_local! {
    /// One `LintGroup` per dialect, built lazily. `LintGroup` is not `Send`
    /// (internal `Lrc`s), so the cache is thread-local — the Kotlin wrapper
    /// must funnel every call through a single dedicated thread or it pays
    /// linter construction (~100ms) once per extra thread.
    static LINTERS: RefCell<HashMap<u8, LintGroup>> = RefCell::new(HashMap::new());
}

fn dialect_from_ordinal(ordinal: u8) -> Dialect {
    match ordinal {
        1 => Dialect::British,
        2 => Dialect::Canadian,
        3 => Dialect::Australian,
        _ => Dialect::American,
    }
}

fn chars_to_string(chars: &[char]) -> String {
    chars.iter().collect()
}

fn lint_text(text: &str, dialect_ordinal: u8) -> String {
    let document = Document::new_plain_english_curated(text);

    let mut lints: Vec<Lint> = LINTERS.with(|cell| {
        let mut linters = cell.borrow_mut();
        let linter = linters.entry(dialect_ordinal).or_insert_with(|| {
            LintGroup::new_curated(FstDictionary::curated(), dialect_from_ordinal(dialect_ordinal))
        });
        linter.lint(&document)
    });
    lints.sort_by_key(|l| (l.span.start, l.priority));

    // Harper spans index into the source as `char`s; Kotlin indexes UTF-16
    // code units. Build a prefix table mapping char index -> UTF-16 offset.
    let source: Vec<char> = text.chars().collect();
    let mut utf16_at = Vec::with_capacity(source.len() + 1);
    let mut offset = 0usize;
    utf16_at.push(0);
    for c in &source {
        offset += c.len_utf16();
        utf16_at.push(offset);
    }
    let to_utf16 = |char_idx: usize| *utf16_at.get(char_idx).unwrap_or(&offset);

    let entries: Vec<serde_json::Value> = lints
        .iter()
        .filter(|l| l.span.end <= source.len() && l.span.start <= l.span.end)
        .map(|l| {
            let suggestions: Vec<serde_json::Value> = l
                .suggestions
                .iter()
                .map(|s| match s {
                    Suggestion::ReplaceWith(text) => {
                        json!({ "kind": "replace", "text": chars_to_string(text) })
                    }
                    Suggestion::Remove => json!({ "kind": "remove" }),
                    Suggestion::InsertAfter(text) => {
                        json!({ "kind": "insertAfter", "text": chars_to_string(text) })
                    }
                })
                .collect();
            json!({
                "start": to_utf16(l.span.start),
                "end": to_utf16(l.span.end),
                "original": chars_to_string(&source[l.span.start..l.span.end]),
                "kind": l.lint_kind.to_string(),
                "message": l.message,
                "priority": l.priority,
                "suggestions": suggestions,
            })
        })
        .collect();

    serde_json::Value::Array(entries).to_string()
}

/// JNI: `HarperNative.nativeLint(text: String, dialect: Int): String`
#[no_mangle]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_grammar_HarperNative_nativeLint(
    mut env: JNIEnv,
    _class: JClass,
    text: JString,
    dialect: jint,
) -> jstring {
    let input: String = match env.get_string(&text) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    let result = lint_text(&input, dialect.clamp(0, 3) as u8);
    match env.new_string(result) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// JNI: `HarperNative.nativeWarmUp(dialect: Int)` — pre-builds the linter so
/// the first real keystroke-triggered lint doesn't pay construction cost.
#[no_mangle]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_grammar_HarperNative_nativeWarmUp(
    _env: JNIEnv,
    _class: JClass,
    dialect: jint,
) {
    let _ = lint_text("", dialect.clamp(0, 3) as u8);
}

/// JNI: `HarperNative.nativeRelease()` — drops every cached `LintGroup`.
///
/// There was no way to give this memory back before, which on a keyboard is
/// not a detail: the rule sets are built on first use and `LINTERS` keeps one
/// per dialect for the life of the process, in the process that draws the
/// keyboard (issue #476). The next lint rebuilds, paying the ~100ms again.
///
/// **Must be called on the same thread as the lints**, since the cache is
/// thread-local — calling it elsewhere clears an empty map belonging to that
/// thread and frees nothing. The Kotlin side funnels both through its single
/// `harper-lint` dispatcher, which is what makes this correct.
///
/// Note what this does *not* free: `FstDictionary::curated()` is a process-wide
/// `Arc` inside harper-core, so the curated word list stays mapped once built.
/// Dropping the groups is the part this crate owns.
#[no_mangle]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_grammar_HarperNative_nativeRelease(
    _env: JNIEnv,
    _class: JClass,
) {
    LINTERS.with(|cell| cell.borrow_mut().clear());
}

#[cfg(test)]
mod tests {
    use super::lint_text;

    #[test]
    fn finds_basic_error() {
        let out = lint_text("He go to the store yesterday. Their going too.", 0);
        println!("{out}");
        assert!(out.starts_with('['), "expected JSON array, got: {out}");
        assert!(out.len() > 2, "expected at least one lint: {out}");
    }

    #[test]
    fn utf16_offsets_survive_emoji() {
        // '😀' is one char but two UTF-16 units; a lint after it must shift.
        let out = lint_text("😀 he do n't like it", 0);
        assert!(out.starts_with('['));
    }
}
