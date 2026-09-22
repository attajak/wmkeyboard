package com.wasimaster.wmkeyboard.core.keyman

/**
 * On-disk constants for Keyman's compiled `.kmx` keyboard format.
 *
 * Transcribed from `common/include/kmx_file.h` in the Keyman source (MIT), and
 * cross-checked against `common/web/types/src/kmx/kmx-file-reader.ts`. Every
 * number here is load-bearing; the comments record the ones that are easy to get
 * subtly wrong.
 *
 * The file is little-endian u32 throughout, with UTF-16LE null-terminated
 * strings. Offsets are absolute from the start of the file and an offset of 0
 * means null.
 */
internal object KmxFormat {

    /** `KXTS`. Anything else is not a compiled Keyman keyboard. */
    const val FILE_IDENTIFIER: Int = 0x5354584B

    /** `COMP_KEYBOARD`, and the offset at which `COMP_KEYBOARD_KMXPLUSINFO` begins. */
    const val HEADER_SIZE: Int = 64

    const val STORE_SIZE: Int = 12
    const val KEY_SIZE: Int = 20

    /**
     * `COMP_GROUP` is 24 bytes and the field order is
     * `dpName, dpKeyArray, dpMatch, dpNoMatch, cxKeyArray, fUsingKeys` —
     * `cxKeyArray` sits at offset 16, **after** the two match pointers, not
     * before them. Reading it in the more obvious order yields a plausible but
     * garbage rule count, which is the single easiest way to get this format
     * wrong.
     */
    const val GROUP_SIZE: Int = 24
    const val GROUP_OFF_NAME: Int = 0
    const val GROUP_OFF_KEY_ARRAY: Int = 4
    const val GROUP_OFF_MATCH: Int = 8
    const val GROUP_OFF_NOMATCH: Int = 12
    const val GROUP_OFF_KEY_COUNT: Int = 16
    const val GROUP_OFF_USING_KEYS: Int = 20

    // --- Header field offsets ---
    const val HDR_IDENTIFIER: Int = 0x00
    const val HDR_FILE_VERSION: Int = 0x04
    const val HDR_STORE_COUNT: Int = 0x18
    const val HDR_GROUP_COUNT: Int = 0x1C
    const val HDR_STORE_ARRAY: Int = 0x20
    const val HDR_GROUP_ARRAY: Int = 0x24
    const val HDR_START_GROUP_ANSI: Int = 0x28
    const val HDR_START_GROUP_UNICODE: Int = 0x2C
    const val HDR_FLAGS: Int = 0x30

    /** `StartGroup[n]` uses this rather than 0 to mean "no such group". */
    const val NO_GROUP: Int = -1 // 0xFFFFFFFF

    // --- Versions ---
    const val VERSION_MIN: Int = 0x500
    const val VERSION_MAX: Int = 0x1100

    // --- Escapes inside context and output strings ---

    /**
     * Introduces a one-unit opcode plus its operands. Operands are stored
     * **biased by +1** so that none of them can be a NUL that would terminate
     * the string early — every consumer subtracts one.
     */
    const val UC_SENTINEL: Int = 0xFFFF

    /** Terminates a variable-length [CODE_EXTENDED] run. */
    const val UC_SENTINEL_EXTENDEDEND: Int = 0x10

    const val CODE_ANY: Int = 0x01
    const val CODE_INDEX: Int = 0x02
    const val CODE_CONTEXT: Int = 0x03
    const val CODE_NUL: Int = 0x04
    const val CODE_USE: Int = 0x05
    const val CODE_RETURN: Int = 0x06
    const val CODE_BEEP: Int = 0x07
    const val CODE_DEADKEY: Int = 0x08
    const val CODE_EXTENDED: Int = 0x0A
    const val CODE_SWITCH: Int = 0x0C
    const val CODE_KEY: Int = 0x0D
    const val CODE_CLEARCONTEXT: Int = 0x0E
    const val CODE_CALL: Int = 0x0F
    const val CODE_CONTEXTEX: Int = 0x11
    const val CODE_NOTANY: Int = 0x12
    const val CODE_SETOPT: Int = 0x13
    const val CODE_IFOPT: Int = 0x14
    const val CODE_SAVEOPT: Int = 0x15
    const val CODE_RESETOPT: Int = 0x16
    const val CODE_IFSYSTEMSTORE: Int = 0x17
    const val CODE_SETSYSTEMSTORE: Int = 0x18

    const val CODE_LAST: Int = CODE_SETSYSTEMSTORE

    /**
     * Operand count per opcode, indexed by the opcode itself. `-1` marks a code
     * that is unused, deprecated, or variable-length; a reader that meets one
     * advances a single unit rather than trusting a width, which is what makes
     * the walk tolerant of a malformed file instead of running off the end.
     */
    val CODE_OPERANDS: IntArray = IntArray(CODE_LAST + 1) { -1 }.apply {
        this[CODE_ANY] = 1
        this[CODE_INDEX] = 2
        this[CODE_CONTEXT] = 0
        this[CODE_NUL] = 0
        this[CODE_USE] = 1
        this[CODE_RETURN] = 0
        this[CODE_BEEP] = 0
        this[CODE_DEADKEY] = 1
        this[CODE_EXTENDED] = -1
        this[CODE_SWITCH] = 1
        this[CODE_KEY] = -1
        this[CODE_CLEARCONTEXT] = 0
        this[CODE_CALL] = 1
        this[CODE_CONTEXTEX] = 1
        this[CODE_NOTANY] = 1
        this[CODE_SETOPT] = 2
        this[CODE_IFOPT] = 3
        this[CODE_SAVEOPT] = 1
        this[CODE_RESETOPT] = 1
        this[CODE_IFSYSTEMSTORE] = 3
        this[CODE_SETSYSTEMSTORE] = 2
    }

    // --- COMP_KEY.ShiftFlags ---
    const val LCTRLFLAG: Int = 0x0001
    const val RCTRLFLAG: Int = 0x0002
    const val LALTFLAG: Int = 0x0004
    const val RALTFLAG: Int = 0x0008
    const val K_SHIFTFLAG: Int = 0x0010
    const val K_CTRLFLAG: Int = 0x0020
    const val K_ALTFLAG: Int = 0x0040
    const val CAPITALFLAG: Int = 0x0100
    const val NOTCAPITALFLAG: Int = 0x0200
    const val NUMLOCKFLAG: Int = 0x0400
    const val NOTNUMLOCKFLAG: Int = 0x0800
    const val SCROLLFLAG: Int = 0x1000
    const val NOTSCROLLFLAG: Int = 0x2000

    /** `Key` is a US virtual key code. */
    const val ISVIRTUALKEY: Int = 0x4000

    /** `Key` is a key-cap character, combined with the shift flags. */
    const val VIRTUALCHARKEY: Int = 0x8000

    /** The modifier bits `IsEquivalentShift` compares on. */
    const val K_MODIFIERFLAG: Int = 0x007F

    /**
     * `ShiftFlags and this` distinguishes the three ways `COMP_KEY.Key` can be
     * read: 0 = a literal key-cap character with every other flag ignored,
     * [ISVIRTUALKEY] = a US virtual key, [VIRTUALCHARKEY] = a key cap combined
     * with the modifiers.
     */
    const val KEY_KIND_MASK: Int = ISVIRTUALKEY or VIRTUALCHARKEY

    /** Highest Windows virtual key. Keyboard-allocated `T_`/`U_` codes start above it. */
    const val VK_MAX: Int = 255

    // --- System stores (COMP_STORE.dwSystemID) ---
    const val TSS_NONE: Int = 0
    const val TSS_NAME: Int = 7
    const val TSS_VERSION: Int = 8
    const val TSS_MNEMONIC: Int = 17
    const val TSS_VISUALKEYBOARD: Int = 24
    const val TSS_COMPARISON: Int = 30
    const val TSS_PLATFORM: Int = 31
    const val TSS_BASELAYOUT: Int = 32
    const val TSS_LAYER: Int = 33
    const val TSS_VKDICTIONARY: Int = 34
    const val TSS_LAYOUTFILE: Int = 35
    const val TSS_KEYBOARDVERSION: Int = 36
    const val TSS_TARGETS: Int = 38
    const val TSS_CASEDKEYS: Int = 39

    /**
     * `begin NewContext` and `begin PostKeystroke` (Keyman 15.0). `StartGroup[]`
     * has only two slots, so these live in system stores instead — and the value
     * is not a number but the three-unit string
     * `UC_SENTINEL, CODE_USE, groupIndex + 1`.
     */
    const val TSS_BEGIN_NEWCONTEXT: Int = 40
    const val TSS_BEGIN_POSTKEYSTROKE: Int = 41

    const val TSS_NEWLAYER: Int = 42
    const val TSS_OLDLAYER: Int = 43
    const val TSS_MAX: Int = 44
}
