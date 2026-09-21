package com.wasimaster.wmkeyboard.core.stickers.signal

/** One of the packs Signal ships with. [title] and [author] are names, and are not translated. */
data class SignalBuiltInPack(
    val packId: String,
    val packKey: String,
    val title: String,
    val author: String,
)

/**
 * The packs Signal itself offers a new user, which is as close to a sticker
 * shop as Signal has.
 *
 * The ids and keys are the ones in Signal's own source (`BlessedPacks.kt`, the
 * same list Molly carries), and the names are what each pack's manifest says,
 * written down here so that the list can be drawn without asking a server
 * anything. Only opening one of them does that.
 *
 * Signal and Molly know no other list. Everything past these eleven is found
 * on the web and arrives as a pack link.
 */
object SignalBuiltInPacks {

    val all: List<SignalBuiltInPack> = listOf(
        SignalBuiltInPack(
            "42fb75e1827c0c945cfb5ca0975db03c",
            "eee27e2b9f773e0a55ea24c340b7be858711a6e2bd9b6ee7044343e0e428be65",
            "Rocky Talk", "Lexi Vay",
        ),
        SignalBuiltInPack(
            "ccc89a05dc077856b57351e90697976c",
            "45730e60f09d5566115223744537a6b7d9ea99ceeacb77a1fbd6801b9607fbcf",
            "My Daily Life", "Plastic Thing",
        ),
        SignalBuiltInPack(
            "fb535407d2f6497ec074df8b9c51dd1d",
            "17e971c134035622781d2ee249e6473b774583750b68c11bb82b7509c68b6dfd",
            "Zozo the French Bulldog", "Arrow Bowie",
        ),
        SignalBuiltInPack(
            "3044281a51307306e5442f2e9070953a",
            "c4caaa84397e1a630a5960f54a0b82753c88a5e52e0defe615ba4dd80f130cbf",
            "Croco’s Feelings", "Tiffany Beucher",
        ),
        SignalBuiltInPack(
            "e61fa0867031597467ccc036cc65d403",
            "13ae7b1a7407318280e9b38c1261ded38e0e7138b9f964a6ccbb73e40f737a9b",
            "Swoon / Hands", "Swoon",
        ),
        SignalBuiltInPack(
            "cca32f5b905208b7d0f1e17f23fdc185",
            "8bf8e95f7a45bdeafe0c8f5b002ef01ab95b8f1b5baac4019ccd6b6be0b1837a",
            "Swoon / Faces", "Swoon",
        ),
        SignalBuiltInPack(
            "a2414255948558316f37c1d36c64cd28",
            "fda12937196d236f1ca9e1196a56542e1d1cef6ff84e2be03828717fa20ad366",
            "My Daily Life 2", "Plastic Thing",
        ),
        SignalBuiltInPack(
            "9acc9e8aba563d26a4994e69263e3b25",
            "5a6dff3948c28efb9b7aaf93ecc375c69fc316e78077ed26867a14d10a0f6a12",
            "Bandit the Cat", "Agnes Lee",
        ),
        SignalBuiltInPack(
            "cfc50156556893ef9838069d3890fe49",
            "5f5beab7d382443cb00a1e48eb95297b6b8cadfd0631e5d0d9dc949e6999ff4b",
            "Day by Day", "Miguel Ángel Camprubí",
        ),
        SignalBuiltInPack(
            "684d2b7bcfc2eec6f57f2e7be0078e0f",
            "866e0dcb4a1b25f2b04df270cd742723e4a6555c0a1abc3f3f30dcc5a2010c55",
            "Cozy Season", "Miguel Ángel Camprubí",
        ),
        SignalBuiltInPack(
            "f19548e5afa38d1ce4f5c3191eba5e30",
            "2cb3076740f669aa44c6c063290b249a7d00a4b02ed8f9e9a5b902a37f1bbc41",
            "Chug the Mouse", "Amanda Cotan",
        ),
    )
}
