package app.offlineresearch

import app.offlineresearch.engine.ProcParser
import app.offlineresearch.profiles.ProfileChoice
import app.offlineresearch.profiles.ProfileSelector
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class ProfileSelectorTest {

    private val gb = 1_000_000_000L

    @Test
    fun automaticPicksLowOnAnEightGigabytePhone() {
        // The Redmi 12 5G reports 7,614,048 kB.
        assertEquals("low.json", ProfileSelector.assetFor(ProfileChoice.AUTO, 7_614_048L * 1024))
    }

    @Test
    fun automaticPicksHighOnATwelveGigabytePhone() {
        assertEquals("high.json", ProfileSelector.assetFor(ProfileChoice.AUTO, 11 * gb + 300_000_000))
    }

    @Test
    fun theThresholdItselfIsHigh() {
        assertEquals("high.json", ProfileSelector.assetFor(ProfileChoice.AUTO, ProfileSelector.HIGH_PROFILE_MIN_RAM_BYTES))
        assertEquals("low.json", ProfileSelector.assetFor(ProfileChoice.AUTO, ProfileSelector.HIGH_PROFILE_MIN_RAM_BYTES - 1))
    }

    @Test
    fun aManualChoiceIgnoresRam() {
        assertEquals("high.json", ProfileSelector.assetFor(ProfileChoice.HIGH, 4 * gb))
        assertEquals("low.json", ProfileSelector.assetFor(ProfileChoice.LOW, 16 * gb))
        assertEquals("fast.json", ProfileSelector.assetFor(ProfileChoice.FAST, 16 * gb))
    }

    @Test
    fun choiceParsingIsLenient() {
        assertEquals(ProfileChoice.HIGH, ProfileChoice.parse(" High "))
        assertEquals(ProfileChoice.LOW, ProfileChoice.parse("low"))
        assertEquals(ProfileChoice.FAST, ProfileChoice.parse("fast"))
        assertEquals(ProfileChoice.AUTO, ProfileChoice.parse(null))
        assertEquals(ProfileChoice.AUTO, ProfileChoice.parse("turbo"))
    }

    @Test
    fun bothBundledProfilesExist() {
        // Unit tests run with the module directory (app/) as the working directory.
        for (asset in listOf(ProfileSelector.LOW_ASSET, ProfileSelector.HIGH_ASSET, ProfileSelector.FAST_ASSET)) {
            assertEquals(true, File("../profiles/$asset").isFile)
        }
    }
}

class ProcParserTest {

    private val status = """
        Name:	offlineresearch
        VmPeak:	21034244 kB
        VmHWM:	 2534120 kB
        VmRSS:	 2411896 kB
        RssFile:	 2298004 kB
    """.trimIndent()

    @Test
    fun readsResidentAndPeakMemory() {
        assertEquals(2_411_896, ProcParser.statusKb(status, "VmRSS"))
        assertEquals(2_534_120, ProcParser.statusKb(status, "VmHWM"))
    }

    @Test
    fun aMissingKeyIsMinusOne() {
        assertEquals(-1, ProcParser.statusKb(status, "VmSwap"))
        assertEquals(-1, ProcParser.statusKb("", "VmRSS"))
    }

    @Test
    fun aKeyThatIsAPrefixOfAnotherIsNotConfused() {
        assertEquals(-1, ProcParser.statusKb(status, "Vm"))
        assertEquals(-1, ProcParser.statusKb(status, "Rss"))
    }

    @Test
    fun readsMajorFaultsFromStat() {
        // pid (comm) state ppid pgrp session tty tpgid flags minflt cminflt majflt cmajflt ...
        val stat = "5139 (offlineresearch) S 812 812 0 0 -1 1077952832 91234 0 4321 0 8000 600 0 0 10 -10 40 0"
        assertEquals(4321, ProcParser.majorFaults(stat))
    }

    @Test
    fun aProcessNameWithSpacesAndParenthesesDoesNotShiftTheFields() {
        val stat = "5139 (a b) c) R 1 1 0 0 -1 0 7 0 99 0 1 1"
        assertEquals(99, ProcParser.majorFaults(stat))
    }

    @Test
    fun garbageIsMinusOne() {
        assertEquals(-1, ProcParser.majorFaults(""))
        assertEquals(-1, ProcParser.majorFaults("no parenthesis here"))
        assertEquals(-1, ProcParser.majorFaults("1 (x) S 1 2"))
    }
}
