package org.monogram.core.common

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
            "T-Mobile" to setOf("933", "949", "959", "978", "990", "993", "994", "995"),
            "SberMobile" to setOf("999"),
            "Rostelecom" to setOf("902", "939", "958"),
            "Motiv" to setOf("900", "904", "908", "952", "953"),
            "Tattelecom" to setOf("991")
        ),
        "UA" to mapOf(
            "Kyivstar" to setOf("39", "67", "68", "77", "96", "97", "98"),
            "Vodafone" to setOf("50", "66", "75", "95", "99"),
            "lifecell" to setOf("63", "73", "93"),
            "Intertelecom" to setOf("94"),
            "TriMob" to setOf("91"),
            "PEOPLEnet" to setOf("92")
        ),
        "KZ" to mapOf(
            "Beeline" to setOf("705", "706", "771", "776", "777"),
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
            "Beeline" to setOf("90", "91", "20", "92"),
            "Ucell" to setOf("93", "94", "50"),
            "Mobiuz" to setOf("88", "97", "87"),
            "Uztelecom" to setOf("95", "99", "77"),
            "Humans" to setOf("33"),
            "Perfectum" to setOf("98", "80")
        ),
        "AZ" to mapOf(
            "Azercell" to setOf("50", "51", "10", "60"),
            "Bakcell" to setOf("55", "99"),
            "Nar" to setOf("70", "77")
        ),
        "AM" to mapOf(
            "Team" to setOf("91", "96", "99", "43"),
            "Viva-MTS" to setOf("77", "93", "94", "98"),
            "Ucom" to setOf("55", "95", "41", "44")
        ),
        "GE" to mapOf(
            "Magti" to setOf("511", "551", "591", "595", "596", "598", "599"),
            "Silknet" to setOf("514", "555", "557", "558", "570", "577", "578", "593"),
            "Cellfie" to setOf("568", "571", "574", "579", "592", "597")
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
        "TM" to mapOf(
            "Altyn Asyr" to setOf("61", "62", "63", "64", "65", "66", "71")
        ),
        "CN" to mapOf(
            "China Mobile" to setOf(
                "134", "135", "136", "137", "138", "139", "147", "148",
                "150", "151", "152", "157", "158", "159",
                "172", "178", "182", "183", "184", "187", "188",
                "195", "197", "198"
            ),
            "China Unicom" to setOf(
                "130", "131", "132", "145", "146",
                "155", "156", "166", "167", "171", "175", "176", "185", "186", "196"
            ),
            "China Telecom" to setOf(
                "133", "149", "153", "173", "174", "177",
                "180", "181", "189", "190", "191", "193", "199"
            ),
            "China Broadnet" to setOf("192")
        ),
        "DE" to mapOf(
            "Telekom" to setOf("151", "160", "170", "171", "175"),
            "Vodafone" to setOf("152", "162", "172", "173", "174"),
            "O2" to setOf("155", "157", "159", "163", "176", "177", "178", "179"),
            "1&1" to setOf("156")
        ),
        "IT" to mapOf(
            "TIM" to setOf("330", "331", "333", "334", "335", "336", "337", "338", "339", "360", "366", "368"),
            "Vodafone" to setOf("340", "342", "344", "345", "346", "347", "348", "349"),
            "WindTre" to setOf("320", "324", "327", "328", "329", "380", "388", "389", "391", "392", "393"),
            "Iliad" to setOf("351", "352"),
            "Fastweb" to setOf("373", "375")
        ),
        "TR" to mapOf(
            "Turkcell" to setOf("530", "531", "532", "533", "534", "535", "536", "537", "538", "539"),
            "Vodafone" to setOf("540", "541", "542", "543", "544", "545", "546", "547", "548", "549"),
            "Turk Telekom" to setOf(
                "500", "501", "505", "506", "507",
                "551", "552", "553", "554", "555", "559"
            )
        ),
        "PL" to mapOf(
            "Orange" to setOf("500", "501", "502", "503", "504", "505", "506", "507", "508", "509", "690", "691", "692", "693", "694", "695", "696", "697", "698", "699"),
            "Plus" to setOf("600", "601", "602", "603", "604", "605", "606", "607", "608", "609", "660", "661", "662", "663", "664", "665", "666", "667", "668", "669"),
            "Play" to setOf("530", "531", "532", "533", "534", "535", "536", "537", "538", "539", "570", "571", "572", "573", "574", "575", "576", "577", "578", "579", "730", "731", "732", "733", "734", "735", "736", "737", "738", "739", "780", "781", "782", "783", "784", "785", "786", "787", "788", "789"),
            "T-Mobile" to setOf("510", "511", "512", "513", "514", "515", "516", "517", "518", "519", "880", "881", "882", "883", "884", "885", "886", "887", "888", "889")
        ),
        "ES" to mapOf(
            "Movistar" to setOf("606", "608", "609", "616", "618", "619", "620", "626", "628", "629", "630", "636", "638", "639", "646", "648", "649", "650", "659", "660", "669", "676", "679", "680", "681", "682", "683", "686", "689", "690", "696", "699", "717"),
            "Vodafone" to setOf("600", "603", "607", "610", "617", "627", "634", "637", "647", "661", "662", "663", "664", "666", "667", "670", "671", "672", "673", "674", "677", "678", "687", "697", "711", "720", "727"),
            "Orange" to setOf("605", "615", "625", "635", "645", "651", "652", "653", "654", "655", "656", "657", "658", "665", "675", "685", "691", "692", "721", "728", "747", "748"),
            "Yoigo" to setOf("613", "622", "623", "633", "712", "722"),
            "Digi" to setOf("624", "641", "642", "643")
        ),
        "GB" to mapOf(
            "EE" to setOf("75", "79"),
            "O2" to setOf("77"),
            "Vodafone" to setOf("78"),
            "Three" to setOf("73", "74")
        ),
        "PT" to mapOf(
            "Vodafone" to setOf("91"),
            "NOS" to setOf("93"),
            "MEO" to setOf("96")
        ),
        "GR" to mapOf(
            "Cosmote" to setOf("697", "698"),
            "Vodafone" to setOf("694", "695"),
            "Nova" to setOf("690", "693", "699")
        ),
        "RO" to mapOf(
            "Vodafone" to setOf("72", "73"),
            "Orange" to setOf("74", "75"),
            "Telekom" to setOf("76"),
            "Digi" to setOf("77")
        ),
        "HU" to mapOf(
            "Yettel" to setOf("20"),
            "Telekom" to setOf("30"),
            "One" to setOf("70")
        ),
        "BG" to mapOf(
            "Vivacom" to setOf("87"),
            "A1" to setOf("88"),
            "Yettel" to setOf("89")
        ),
        "AT" to mapOf(
            "A1" to setOf("664", "680"),
            "Magenta" to setOf("650", "676"),
            "Drei" to setOf("660", "699"),
            "HoT" to setOf("677")
        ),
        "CH" to mapOf(
            "Swisscom" to setOf("74", "75", "79"),
            "Sunrise" to setOf("76", "77"),
            "Salt" to setOf("78")
        ),
        "BE" to mapOf(
            "Proximus" to setOf("47"),
            "Base" to setOf("48"),
            "Orange" to setOf("49"),
            "Mobile Vikings" to setOf("456")
        ),
        "SE" to mapOf(
            "Telia" to setOf("70"),
            "Telenor" to setOf("72"),
            "Tre" to setOf("73"),
            "Tele2" to setOf("76")
        ),
        "FI" to mapOf(
            "Telia" to setOf("40", "42"),
            "DNA" to setOf("41", "44"),
            "Elisa" to setOf("46", "50")
        ),
        "CZ" to mapOf(
            "O2" to setOf("601", "602", "606", "607", "72"),
            "T-Mobile" to setOf("603", "604", "605", "73"),
            "Vodafone" to setOf("608", "77")
        ),
        "HR" to mapOf(
            "A1" to setOf("91", "92"),
            "Telemach" to setOf("95"),
            "HT" to setOf("98", "99")
        ),
        "RS" to mapOf(
            "A1" to setOf("60", "61"),
            "Yettel" to setOf("62", "63"),
            "MTS" to setOf("64", "65", "66")
        ),
        "SK" to mapOf(
            "Telekom" to setOf("903", "904", "949"),
            "Orange" to setOf("905", "906", "907", "908"),
            "O2" to setOf("940", "944", "948"),
            "4ka" to setOf("950")
        ),
        "IE" to mapOf(
            "Three" to setOf("83", "86"),
            "Eir" to setOf("85"),
            "Vodafone" to setOf("87")
        ),
        "AE" to mapOf(
            "e&" to setOf("50", "54", "56"),
            "du" to setOf("52", "55", "58"),
            "Virgin Mobile" to setOf("53")
        ),
        "EG" to mapOf(
            "Vodafone" to setOf("10"),
            "e&" to setOf("11"),
            "Orange" to setOf("12"),
            "WE" to setOf("15")
        ),
        "SA" to mapOf(
            "STC" to setOf("50", "53", "55"),
            "Mobily" to setOf("54", "56", "57"),
            "Zain" to setOf("58", "59")
        ),
        "IL" to mapOf(
            "Pelephone" to setOf("50"),
            "Cellcom" to setOf("52"),
            "Hot Mobile" to setOf("53"),
            "Partner" to setOf("54"),
            "Golan Telecom" to setOf("58")
        ),
        "ID" to mapOf(
            "Telkomsel" to setOf("811", "812", "813", "821", "822", "823", "851", "852", "853"),
            "Indosat" to setOf("814", "815", "816", "855", "856", "857", "858"),
            "XL" to setOf("817", "818", "819", "859", "877", "878"),
            "AXIS" to setOf("831", "832", "833", "838"),
            "Tri" to setOf("895", "896", "897", "898", "899"),
            "Smartfren" to setOf("881", "882", "883", "884", "885", "886", "887", "888", "889")
        ),
        "VN" to mapOf(
            "Viettel" to setOf("32", "33", "34", "35", "36", "37", "38", "39", "86", "96", "97", "98"),
            "Vinaphone" to setOf("81", "82", "83", "84", "85", "88", "91", "94"),
            "MobiFone" to setOf("70", "76", "77", "78", "79", "89", "90", "93"),
            "Vietnamobile" to setOf("56", "58", "92"),
            "Gmobile" to setOf("59", "99")
        ),
        "MY" to mapOf(
            "Maxis" to setOf("12", "17"),
            "Celcom" to setOf("13", "19"),
            "Digi" to setOf("16"),
            "U Mobile" to setOf("18")
        ),
        "MN" to mapOf(
            "Unitel" to setOf("80", "88", "89"),
            "Skytel" to setOf("90", "91", "96"),
            "G-Mobile" to setOf("93", "98"),
            "Mobicom" to setOf("94", "95", "99")
        )
    )

    fun operatorFor(phone: String, countryIso: String?): String? {
        if (countryIso == null) return null
        val operators = operatorsByCountry[countryIso] ?: return null
        val digits = phone.filter { it.isDigit() }

        val countryCode = CountryManager.countryForIso(countryIso)?.code ?: return null
        if (!digits.startsWith(countryCode)) return null

        val phoneWithoutCode = digits.removePrefix(countryCode)

        if (countryIso == "RU") {
            val defCode = phoneWithoutCode.take(3)
            if (defCode == "999") return "Yota/SberMobile"
            if (defCode == "995") return "Yota/T-Mobile"
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
}
