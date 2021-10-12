/*
 *  Copyright (c) 2021 David Allison <davidallisongithub@gmail.com>
 *
 *  This program is free software; you can redistribute it and/or modify it under
 *  the terms of the GNU General Public License as published by the Free Software
 *  Foundation; either version 3 of the License, or (at your option) any later
 *  version.
 *
 *  This program is distributed in the hope that it will be useful, but WITHOUT ANY
 *  WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 *  PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License along with
 *  this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.ichi2.anki.cardviewer

import android.content.SharedPreferences
import android.content.res.Resources
import android.text.TextUtils
import androidx.annotation.VisibleForTesting
import com.ichi2.anki.R
import com.ichi2.libanki.Card
import com.ichi2.libanki.Utils
import com.ichi2.utils.DiffEngine
import com.ichi2.utils.JSONArray
import java.util.*
import java.util.regex.Matcher
import java.util.regex.Pattern

class TypeAnswer(
    @get:JvmName("useInputTag") val useInputTag: Boolean,
    @get:JvmName("doNotUseCodeFormatting") val doNotUseCodeFormatting: Boolean,
    /** Preference: Whether the user wants to focus "type in answer" */
    val autoFocus: Boolean
) {

    /** The correct answer in the compare to field if answer should be given by learner. Null if no answer is expected. */
    var correct: String? = null
        private set

    /** What the learner actually typed (externally mutable) */
    var input = ""
    /** Font face of the 'compare to' field */
    var font = ""
        private set
    /** The font size of the 'compare to' field */
    var size = 0
        private set

    /**
     * Optional warning for when a typed answer can't be displayed
     *
     * * empty card [R.string.empty_card_warning]
     * * unknown field specified [R.string.unknown_type_field_warning]
     * */
    var warning: String? = null
        private set

    /**
     * Extract type answer/cloze text and font/size
     * @param card The next card to display
     */
    fun updateInfo(card: Card, res: Resources) {
        correct = null
        input = ""
        val q = card.q(false)
        val m = PATTERN.matcher(q)
        var clozeIdx = 0
        if (!m.find()) {
            return
        }
        var fldTag = m.group(1)!!
        // if it's a cloze, extract data
        if (fldTag.startsWith("cloze:")) {
            // get field and cloze position
            clozeIdx = card.getOrd() + 1
            fldTag = fldTag.split(":").toTypedArray()[1]
        }
        // loop through fields for a match
        val flds: JSONArray = card.model().getJSONArray("flds")
        for (fld in flds.jsonObjectIterable()) {
            val name = fld.getString("name")
            if (name == fldTag) {
                correct = card.note().getItem(name)
                if (clozeIdx != 0) {
                    // narrow to cloze
                    correct = contentForCloze(correct!!, clozeIdx)
                }
                font = fld.getString("font")
                size = fld.getInt("size")
                break
            }
        }
        when (correct) {
            null -> {
                warning = if (clozeIdx != 0) {
                    res.getString(R.string.empty_card_warning)
                } else {
                    res.getString(R.string.unknown_type_field_warning, fldTag)
                }
            }
            "" -> {
                correct = null
            }
            else -> {
                warning = null
            }
        }
    }

    /**
     * Fill the placeholder for the type comparison. Show the correct answer, and the comparison if appropriate.
     *
     * @param answer The answer text
     * @param userAnswer Text typed by the user, or empty.
     * @param correctAnswer The correct answer, taken from the note.
     * @return The formatted answer text
     */
    fun typeAnswerFilter(answer: String, userAnswer: String, correctAnswer: String): String {
        val m: Matcher = PATTERN.matcher(answer)
        val diffEngine = DiffEngine()
        val sb = StringBuilder()
        sb.append(if (doNotUseCodeFormatting) "<div><span id=\"typeans\">" else "<div><code id=\"typeans\">")

        // We have to use Matcher.quoteReplacement because the inputs here might have $ or \.
        if (userAnswer.isNotEmpty()) {
            // The user did type something.
            if (userAnswer == correctAnswer) {
                // and it was right.
                sb.append(Matcher.quoteReplacement(DiffEngine.wrapGood(correctAnswer)))
                sb.append("<span id=\"typecheckmark\">\u2714</span>") // Heavy check mark
            } else {
                // Answer not correct.
                // Only use the complex diff code when needed, that is when we have some typed text that is not
                // exactly the same as the correct text.
                val diffedStrings = diffEngine.diffedHtmlStrings(correctAnswer, userAnswer)
                // We know we get back two strings.
                sb.append(Matcher.quoteReplacement(diffedStrings[0]))
                sb.append("<br><span id=\"typearrow\">&darr;</span><br>")
                sb.append(Matcher.quoteReplacement(diffedStrings[1]))
            }
        } else {
            if (!useInputTag) {
                sb.append(Matcher.quoteReplacement(DiffEngine.wrapMissing(correctAnswer)))
            } else {
                sb.append(Matcher.quoteReplacement(correctAnswer))
            }
        }
        sb.append(if (doNotUseCodeFormatting) "</span></div>" else "</code></div>")
        return m.replaceAll(sb.toString())
    }

    companion object {
        @JvmField
        /** Regular expression in card data for a 'type answer' after processing has occurred */
        val PATTERN: Pattern = Pattern.compile("\\[\\[type:(.+?)]]")

        @JvmStatic
        fun createInstance(preferences: SharedPreferences): TypeAnswer {
            return TypeAnswer(
                useInputTag = preferences.getBoolean("useInputTag", false),
                doNotUseCodeFormatting = preferences.getBoolean("noCodeFormatting", false),
                autoFocus = preferences.getBoolean("autoFocusTypeInAnswer", false)
            )
        }

        /**
         * Clean up the typed answer text, so it can be used for the comparison with the correct answer
         *
         * @param answer The answer text typed by the user.
         * @return The typed answer text, cleaned up.
         */
        @JvmStatic
        fun cleanTypedAnswer(answer: String?): String? {
            return if (answer == null || "" == answer) {
                ""
            } else Utils.nfcNormalized(answer.trim())
        }

        /**
         * Return the correct answer to use for {{type::cloze::NN}} fields.
         *
         * @param txt The field text with the clozes
         * @param idx The index of the cloze to use
         * @return If the cloze strings are the same, return a single cloze string, otherwise, return
         * a string with a comma-separeted list of strings with the correct index.
         */
        @VisibleForTesting
        fun contentForCloze(txt: String, idx: Int): String? {
            // In Android, } should be escaped
            val re = Pattern.compile("\\{\\{c$idx::(.+?)\\}\\}")
            val m = re.matcher(txt)
            val matches: MutableList<String?> = ArrayList()
            var groupOne: String
            while (m.find()) {
                groupOne = m.group(1)!!
                val colonColonIndex = groupOne.indexOf("::")
                if (colonColonIndex > -1) {
                    // Cut out the hint.
                    groupOne = groupOne.substring(0, colonColonIndex)
                }
                matches.add(groupOne)
            }
            val uniqMatches: Set<String?> = HashSet(matches) // Allow to check whether there are distinct strings

            // Make it consistent with the Desktop version (see issue #8229)
            return if (uniqMatches.size == 1) {
                matches[0]
            } else {
                TextUtils.join(", ", matches)
            }
        }
    }
}