package org.monogram.presentation.core.util

object OperatorManager {
    private val operatorsByCountry = mapOf(
        "RU" to mapOf(
            "MTS" to setOf(
                "910", "911", "912", "913", "914", "915", "916", "917", "918", "919",
                "980", "981", "982", "983", "984", "985", "986", "987", "988", "989",
                "978"
            ),
            "MegaFon" to setOf(
                "920", "921", "922", "923", "924", "925", "926", "927", "928", "929",
                "930", "931", "932", "933", "934", "935", "936", "937", "938", "939",
                "997"
            ),
            "Beeline" to setOf(
                "900", "902", "903", "904", "905", "906", "908", "909",
                "960", "961", "962", "963", "964", "965", "966", "967", "968", "969"
            ),
            "Tele2" to setOf(
                "901", "902", "904", "908", "950", "951", "952", "953", "958", "977",
                "991", "992", "993", "994", "995", "996", "999"
            ),
            "Yota" to setOf("991", "995", "996", "999"),
            "Tinkoff" to setOf("995"),
            "SberMobile" to setOf("999"),
            "Rostelecom" to setOf("902", "939", "958"),
            "Motiv" to setOf("900", "904", "908", "952", "953"),
            "Tattelecom" to setOf("991")
        ),
        "UA" to mapOf(
            "Kyivstar" to setOf("67", "68", "96", "97", "98"),
            "Vodafone" to setOf("50", "66", "95", "99"),
            "lifecell" to setOf("63", "73", "93"),
            "Intertelecom" to setOf("94"),
            "TriMob" to setOf("91")
        ),
        "KZ" to mapOf(
            "Beeline" to setOf("705", "771", "776", "777"),
            "Kcell" to setOf("701", "702", "775", "778"),
            "Tele2" to setOf("707", "747"),
            "Altel" to setOf("700", "708"),
            "Activ" to setOf("701", "702")
        ),
        "BY" to mapOf(
            "A1" to setOf("291", "293", "296", "299", "44"),
            "MTS" to setOf("292", "295", "297", "298", "33"),
            "life:)" to setOf("25")
        ),
        "UZ" to mapOf(
            "Beeline" to setOf("90", "91"),
            "Ucell" to setOf("93", "94"),
            "Mobiuz" to setOf("88", "97"),
            "Uztelecom" to setOf("95", "99"),
            "Humans" to setOf("33")
        ),
        "AZ" to mapOf(
            "Azercell" to setOf("50", "51"),
            "Bakcell" to setOf("55", "99"),
            "Nar" to setOf("70", "77")
        ),
        "AM" to mapOf(
            "Team" to setOf("91", "96", "99"),
            "Viva-MTS" to setOf("77", "93", "94", "98"),
            "Ucom" to setOf("55", "95")
        ),
        "GE" to mapOf(
            "Magti" to setOf("514", "577", "591", "598"),
            "Silknet" to setOf("551", "555", "558", "592", "593", "595", "597", "599"),
            "Cellfie" to setOf("511", "568", "571", "574", "579", "596")
        ),
        "KG" to mapOf(
            "Mega" to setOf("55", "75", "99"),
            "O!" to setOf("50", "70"),
            "Beeline" to setOf("22", "77")
        ),
        "TJ" to mapOf(
            "Babilon-M" to setOf("918", "98"),
            "Tcell" to setOf("92", "93"),
            "MegaFon" to setOf("90", "88", "55"),
            "Zet-Mobile" to setOf("91")
        ),
        "MD" to mapOf(
            "Orange" to setOf("61", "62", "68", "69"),
            "Moldcell" to setOf("60", "78", "79"),
            "Unite" to setOf("67")
        ),
        "CN" to mapOf(
            "China Mobile" to setOf(
                "134",
                "135",
                "136",
                "137",
                "138",
                "139",
                "147",
                "150",
                "151",
                "152",
                "157",
                "158",
                "159",
                "178",
                "182",
                "183",
                "184",
                "187",
                "188",
                "198"
            ),
            "China Unicom" to setOf("130", "131", "132", "145", "155", "156", "166", "175", "176", "185", "186"),
            "China Telecom" to setOf("133", "149", "153", "173", "177", "180", "181", "189", "191", "199")
        ),
        "DE" to mapOf(
            "Telekom" to setOf("151", "160", "170", "171", "175"),
            "Vodafone" to setOf("152", "162", "172", "173", "174"),
            "O2" to setOf("157", "159", "176", "179")
        ),
        "IT" to mapOf(
            "TIM" to setOf("33", "36"),
            "Vodafone" to setOf("34"),
            "WindTre" to setOf("32", "38"),
            "Iliad" to setOf("35")
        )
    )

    fun detectOperator(phone: String, countryIso: String?): String? {
        if (countryIso == null) return null
        val operators = operatorsByCountry[countryIso] ?: return null
        val digits = phone.filter { it.isDigit() }

        val countryCode = getCountryCode(countryIso) ?: return null
        if (!digits.startsWith(countryCode)) return null

        val phoneWithoutCode = digits.removePrefix(countryCode)

        if (countryIso == "RU") {
            val defCode = phoneWithoutCode.take(3)
            if (defCode == "999") return "Yota/SberMobile"
            if (defCode == "995") return "Yota/Tinkoff"
            if (defCode == "991") return "Yota/Tele2/Tattelecom"

            val matches = operators.filter { it.value.contains(defCode) }.keys
            return if (matches.isEmpty()) null else matches.joinToString("/")
        }

        val matches = mutableListOf<String>()
        for ((operator, prefixes) in operators) {
            if (prefixes.any { phoneWithoutCode.startsWith(it) }) {
                matches.add(operator)
            }
        }

        return if (matches.isEmpty()) null else matches.joinToString("/")
    }

    private fun getCountryCode(iso: String): String? = when (iso) {
        "RU" -> "7"
        "UA" -> "380"
        "KZ" -> "7"
        "BY" -> "375"
        "UZ" -> "998"
        "AZ" -> "994"
        "AM" -> "374"
        "GE" -> "995"
        "KG" -> "996"
        "TJ" -> "992"
        "MD" -> "373"
        "CN" -> "86"
        "DE" -> "49"
        "IT" -> "39"
        else -> null
    }
}