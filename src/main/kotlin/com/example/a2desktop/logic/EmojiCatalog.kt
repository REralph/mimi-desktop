package com.example.a2desktop.logic

// The emoji the picker can browse and search: things that turn up on a calendar (chores, appointments, pets, food, travel, fun),
// every one in Unicode 12 or older so it draws on any Windows. Each is one emoji followed by the words it is found by; the
// first word is its name. Typing or pasting any other emoji still works — this is only what the picker lists.

/** One emoji of the picker: [name] is its first word, [words] everything it is found by, [category] the group it is listed in. */
data class EmojiEntry(val emoji: String, val words: List<String>, val category: String) {
    val name: String get() = words.first()
}

private val CATALOG_TEXT: List<Pair<String, String>> = listOf(
    "Home and chores" to """
        🛏️ bed bedroom sheets cover duvet bedding
        🧺 laundry basket washing clothes
        🧹 broom sweep clean cleaning hoover
        🧼 soap wash clean
        🧽 sponge scrub clean
        🧴 lotion bottle cream moisturiser sunscreen skincare
        🚿 shower wash
        🛁 bath bathtub
        🚰 tap water drinking change
        🚽 toilet bathroom
        🗑️ bin rubbish trash waste
        🔑 key keys lock
        🚪 door
        🪑 chair seat
        🛋️ sofa couch lounge
        💡 bulb light lamp
        🔌 plug electric power
        🔋 battery charge
        🧯 extinguisher fire safety
        🔧 wrench tool repair fix
        🔨 hammer build diy
        🧰 toolbox tools
        📦 box parcel package delivery
        📬 post mail letterbox
        🧻 toilet roll paper
        🌱 seedling plant grow garden
        🌿 herb plant garden
        🌵 cactus plant
    """,
    "Health and body" to """
        ✂️ scissors haircut cut barber hair trim
        💇 haircut hair salon hairdresser
        💈 barber pole shave
        💅 nails manicure
        🦷 tooth teeth dentist
        🩺 stethoscope doctor gp check-up
        💊 pill medicine tablet vitamin supplement
        💉 syringe injection vaccine jab blood
        🩹 plaster bandage
        🏥 hospital clinic
        🧬 dna genetics
        👁️ eye optician eyes
        👓 glasses optician specs
        🧠 brain
        🦴 bone
        💪 muscle strong gym arm
        🏋️ weights gym lifting workout training
        🏃 run running jog
        🚴 cycling cyclist ride
        🧘 yoga meditate stretch
        🛌 resting sleep bed
        😴 sleepy tired sleep
        🌡️ thermometer temperature fever
    """,
    "Appointments and work" to """
        💼 briefcase work job
        📞 phone call telephone
        📱 mobile phone
        💻 laptop computer
        📧 email mail
        📅 calendar date appointment
        📆 calendar date
        ⏰ alarm clock time
        ⏳ hourglass wait
        📝 memo note write
        📋 clipboard list
        📌 pin
        📎 paperclip
        📚 books study
        🎓 graduation education course
        ✏️ pencil write
        🖊️ pen sign
        💳 card payment bank credit
        💷 pound money cash
        💰 money bag savings
        🏦 bank
        🧾 receipt bill invoice tax
        📈 chart growth
        🔒 lock secure
        🖨️ printer print
        🔔 bell reminder
    """,
    "Pets and nature" to """
        🐠 fish tropical aquarium tank pet
        🐟 fish
        🐡 pufferfish fish
        🐶 dog puppy pet walk
        🐱 cat kitten pet
        🐰 rabbit bunny pet
        🐹 hamster pet
        🐦 bird pet
        🐢 turtle tortoise pet
        🐍 snake pet
        🦎 lizard gecko pet
        🐞 ladybird bug insect
        🌳 tree
        🌸 blossom flower
        🌻 sunflower flower
        💧 drop water
        🌊 wave sea
        ☀️ sun sunny
        🌧️ rain
        ❄️ snow cold
        🔥 fire hot
    """,
    "Food and drink" to """
        🍽️ plate dinner meal restaurant eat
        🍕 pizza
        🍔 burger
        🍟 chips fries
        🍝 pasta spaghetti
        🍣 sushi
        🥗 salad
        🍎 apple fruit
        🍌 banana fruit
        🥑 avocado
        🥦 broccoli vegetables
        🍞 bread
        🥚 egg
        🧀 cheese
        🍗 chicken
        🥩 steak meat
        ☕ coffee tea cup
        🍵 tea cup
        🥤 drink cup
        🥛 milk glass
        🍺 beer pub
        🍷 wine
        🍰 cake
        🎂 birthday cake
        🍫 chocolate
        🍦 ice cream
        🍪 cookie biscuit
        🛒 shopping cart groceries supermarket
    """,
    "Travel and places" to """
        🚗 car drive vehicle mot
        🚕 taxi cab
        🚌 bus
        🚆 train rail
        🚇 metro tube underground
        ✈️ plane flight airport holiday
        🚲 bicycle bike
        🛵 scooter moped
        ⛽ fuel petrol garage
        🚦 traffic lights
        🏠 house home
        🏢 office building
        🏨 hotel
        🏖️ beach holiday
        ⛰️ mountain hill hike
        🏕️ camping
        🗺️ map
        🧳 luggage suitcase travel pack
        🎫 ticket
    """,
    "Fun and sport" to """
        🎉 party celebrate
        🎈 balloon
        🎁 gift present
        🎬 film movie cinema
        🎮 game gaming
        🎵 music
        🎸 guitar
        🎤 microphone sing karaoke
        📷 camera photo
        ⚽ football soccer
        🏀 basketball
        🎾 tennis
        🏊 swim swimming pool
        🥊 boxing
        🎯 target darts goal
        🏆 trophy win
        🎲 dice board game
        🧩 puzzle
        📺 television tv
        📖 book read
        🎨 art paint
        🎭 theatre
    """,
    "Faces and symbols" to """
        😀 smile happy
        😊 smiling happy
        😎 cool sunglasses
        🥳 party celebrate
        🤒 ill sick unwell
        🤕 hurt injured
        😷 mask
        👽 alien
        👍 thumbs up ok
        👋 wave hello
        🙏 thanks please
        👶 baby
        ❤️ heart love
        ✅ check done tick
        ❌ cross no cancel
        ⚠️ warning
        ❗ important exclamation
        ❓ question
        🔁 repeat loop
        🚫 prohibited banned
        ⭐ star favourite
    """,
)

/** Every emoji of the picker, in the order it lists them. */
val EMOJI_CATALOG: List<EmojiEntry> = CATALOG_TEXT.flatMap { (category, text) ->
    text.lines().map { it.trim() }.filter { it.isNotEmpty() }.map { line ->
        val parts = line.split(' ').filter { it.isNotEmpty() }
        EmojiEntry(parts.first(), parts.drop(1), category)
    }
}

/** The picker's groups, in order, each with its emoji. */
val EMOJI_CATEGORIES: List<Pair<String, List<EmojiEntry>>> = EMOJI_CATALOG.groupBy { it.category }.toList()

/**
 * The emoji whose words fit [query] (nothing for a blank query): every word typed must be the start of one of the entry's
 * words — "hair" and "cut" both find the scissors, "hair" does not find a chair. Words that match whole come first, then ones
 * that only start with it; the picker's own order breaks ties.
 */
fun searchEmojis(query: String, catalog: List<EmojiEntry> = EMOJI_CATALOG): List<EmojiEntry> {
    val tokens = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (tokens.isEmpty()) return emptyList()
    return catalog
        .mapNotNull { entry ->
            var score = 0
            for (token in tokens) {
                score += when {
                    entry.words.any { it == token } -> 3
                    entry.words.any { it.startsWith(token) } -> 2
                    else -> return@mapNotNull null
                }
            }
            entry to score
        }
        .sortedByDescending { it.second }
        .map { it.first }
}
