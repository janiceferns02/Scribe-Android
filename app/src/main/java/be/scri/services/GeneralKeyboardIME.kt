// SPDX-License-Identifier: GPL-3.0-or-later

/**
 * The base keyboard input method (IME) imported into all language keyboards.
 */

package be.scri.services

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PorterDuff
import android.inputmethodservice.InputMethodService
import android.text.InputType
import android.text.InputType.TYPE_CLASS_DATETIME
import android.text.InputType.TYPE_CLASS_NUMBER
import android.text.InputType.TYPE_CLASS_PHONE
import android.text.InputType.TYPE_MASK_CLASS
import android.text.TextUtils
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.EditorInfo.IME_ACTION_NONE
import android.view.inputmethod.EditorInfo.IME_FLAG_NO_ENTER_ACTION
import android.view.inputmethod.EditorInfo.IME_MASK_ACTION
import android.view.inputmethod.ExtractedTextRequest
import android.widget.Button
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import be.scri.R
import be.scri.R.color.md_grey_black_dark
import be.scri.R.color.white
import be.scri.databinding.KeyboardViewCommandOptionsBinding
import be.scri.databinding.KeyboardViewKeyboardBinding
import be.scri.helpers.DatabaseHelper
import be.scri.helpers.HintUtils
import be.scri.helpers.KeyboardBase
import be.scri.helpers.SHIFT_OFF
import be.scri.helpers.SHIFT_ON_ONE_CHAR
import be.scri.helpers.SHIFT_ON_PERMANENT
import be.scri.helpers.english.ENInterfaceVariables
import be.scri.helpers.french.FRInterfaceVariables
import be.scri.helpers.german.DEInterfaceVariables
import be.scri.helpers.italian.ITInterfaceVariables
import be.scri.helpers.portuguese.PTInterfaceVariables
import be.scri.helpers.russian.RUInterfaceVariables
import be.scri.helpers.spanish.ESInterfaceVariables
import be.scri.helpers.swedish.SVInterfaceVariables
import be.scri.views.KeyboardView

// based on https://www.androidauthority.com/lets-build-custom-keyboard-android-832362/

@Suppress("TooManyFunctions", "LargeClass")
abstract class GeneralKeyboardIME(
    var language: String,
) : InputMethodService(),
    KeyboardView.OnKeyboardActionListener {
    abstract fun getKeyboardLayoutXML(): Int

    abstract val keyboardLetters: Int
    abstract val keyboardSymbols: Int
    abstract val keyboardSymbolShift: Int

    abstract var keyboard: KeyboardBase?
    abstract var keyboardView: KeyboardView?
    abstract var lastShiftPressTS: Long
    abstract var keyboardMode: Int
    abstract var inputTypeClass: Int
    abstract var enterKeyType: Int
    abstract var switchToLetters: Boolean
    abstract var hasTextBeforeCursor: Boolean
    abstract var binding: KeyboardViewCommandOptionsBinding

    private var pluralBtn: Button? = null
    private var emojiBtnPhone1: Button? = null
    private var emojiSpacePhone: View? = null
    private var emojiBtnPhone2: Button? = null
    private var emojiBtnTablet1: Button? = null
    private var emojiSpaceTablet1: View? = null
    private var emojiBtnTablet2: Button? = null
    private var emojiSpaceTablet2: View? = null
    private var emojiBtnTablet3: Button? = null

    private var genderSuggestionLeft: Button? = null
    private var genderSuggestionRight: Button? = null
    private var isSingularAndPlural: Boolean = false

    // How quickly do we have to double-tap shift to enable permanent caps lock.
    private val shiftPermToggleSpeed: Int = DEFAULT_SHIFT_PERM_TOGGLE_SPEED
    private lateinit var dbHelper: DatabaseHelper
    lateinit var emojiKeywords: HashMap<String, MutableList<String>>
    lateinit var nounKeywords: HashMap<String, List<String>>
    lateinit var pluralWords: List<String>
    lateinit var caseAnnotation: HashMap<String, MutableList<String>>
    var isAutoSuggestEnabled: Boolean = false
    var lastWord: String? = null
    var autosuggestEmojis: MutableList<String>? = null
    var caseAnnotationSuggestion: MutableList<String>? = null
    var nounTypeSuggestion: List<String>? = null
    var checkIfPluralWord: Boolean = false
    private var currentEnterKeyType: Int? = null
    private val commandChar = "⎜"
    val prepAnnotationConversionDict =
        mapOf(
            "German" to mapOf("Acc" to "Akk"),
            "Russian" to
                mapOf(
                    "Acc" to "Вин",
                    "Dat" to "Дат",
                    "Gen" to "Род",
                    "Loc" to "Мес",
                    "Pre" to "Пре",
                    "Ins" to "Инс",
                ),
        )

    val nounAnnotationConversionDict =
        mapOf(
            "Swedish" to mapOf("C" to "U"),
            "Russian" to
                mapOf(
                    "F" to "Ж",
                    "M" to "М",
                    "N" to "Н",
                    "PL" to "МН",
                ),
        )

    // abstract var keyboardViewKeyboardBinding : KeyboardViewKeyboardBinding

    protected var currentState: ScribeState = ScribeState.IDLE
    protected lateinit var keyboardBinding: KeyboardViewKeyboardBinding

    enum class ScribeState {
        IDLE,
        SELECT_COMMAND,
        TRANSLATE,
        CONJUGATE,
        PLURAL,
        SELECT_VERB_CONJUNCTION,
        SELECT_CASE_DECLENSION,
        ALREADY_PLURAL,
        INVALID,
        DISPLAY_INFORMATION,
    }

    override fun onCreate() {
        super.onCreate()
        keyboardBinding = KeyboardViewKeyboardBinding.inflate(layoutInflater)
        keyboard = KeyboardBase(this, getKeyboardLayoutXML(), enterKeyType)
        onCreateInputView()
        setupCommandBarTheme(binding)
    }

    private fun updateCommandBarHintandPrompt(isUserDarkMode: Boolean? = null) {
        val commandBarButton = keyboardBinding.commandBar
        val hintMessage = HintUtils.getCommandBarHint(currentState, language)
        val promptText = HintUtils.getPromptText(currentState, language)
        val promptTextView = keyboardBinding.promptText
        promptTextView?.text = promptText
        commandBarButton.hint = hintMessage

        if (isUserDarkMode == true) {
            commandBarButton.setBackgroundColor(getColor(R.color.special_key_dark))
            promptTextView?.setBackgroundColor(getColor(R.color.special_key_dark))
            keyboardBinding.promptTextBorder?.setBackgroundColor(getColor(R.color.special_key_dark))
        } else {
            commandBarButton.setBackgroundColor(getColor(white))
            promptTextView?.setBackgroundColor(getColor(white))
            keyboardBinding.promptTextBorder?.setBackgroundColor(getColor(white))
        }

        Log.d(
            "KeyboardUpdate",
            "CommandBar Hint Updated: [State: $currentState, Language: $language, Hint: $hintMessage]",
        )
    }

    private fun updateKeyboardMode(isCommandMode: Boolean = false) {
        val sharedPref = getSharedPreferences("app_preferences", MODE_PRIVATE)
        val currentNightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val isSystemDarkMode = currentNightMode == Configuration.UI_MODE_NIGHT_YES
        val isUserDarkMode = sharedPref.getBoolean("dark_mode", isSystemDarkMode)

        if (isUserDarkMode) {
            val color = ContextCompat.getColorStateList(this, R.color.light_key_color)
            keyboardBinding.scribeKey.foregroundTintList = color
        } else {
            val colorLight = ContextCompat.getColorStateList(this, R.color.light_key_text_color)
            keyboardBinding.scribeKey.foregroundTintList = colorLight
        }

        updateCommandBarHintandPrompt(isUserDarkMode)
        enterKeyType =
            if (isCommandMode) {
                KeyboardBase.MyCustomActions.IME_ACTION_COMMAND
            } else {
                currentEnterKeyType!!
            }
        keyboardView?.setKeyboard(keyboard!!)
    }

    fun getIsAccentCharacterDisabled(): Boolean {
        val sharedPref = getSharedPreferences("app_preferences", MODE_PRIVATE)
        val isAccentCharacterDisabled = sharedPref.getBoolean("disable_accent_character_$language", false)
        return isAccentCharacterDisabled
    }

    fun getEnablePeriodAndCommaABC(): Boolean {
        val sharedPref = getSharedPreferences("app_preferences", MODE_PRIVATE)
        val isDisabledPeriodAndCommaABC = sharedPref.getBoolean("period_and_comma_$language", false)
        return isDisabledPeriodAndCommaABC
    }

    private fun updateEnterKeyColor(isDarkMode: Boolean? = null) {
        when (currentState) {
            ScribeState.IDLE -> keyboardView?.setEnterKeyColor(null, isDarkMode = isDarkMode)
            ScribeState.SELECT_COMMAND -> keyboardView?.setEnterKeyColor(null, isDarkMode = isDarkMode)
            else -> keyboardView?.setEnterKeyColor(getColor(R.color.dark_scribe_blue))
        }
        if (isDarkMode == true) {
            val color = ContextCompat.getColorStateList(this, R.color.light_key_color)
            binding.scribeKey.foregroundTintList = color
        } else {
            val colorLight = ContextCompat.getColorStateList(this, R.color.light_key_text_color)
            binding.scribeKey.foregroundTintList = colorLight
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        currentState = ScribeState.IDLE
        switchToCommandToolBar()
        updateUI()
    }

    override fun commitPeriodAfterSpace() {
        if (currentState == ScribeState.IDLE || currentState == ScribeState.SELECT_COMMAND) {
            if (getSharedPreferences("app_preferences", Context.MODE_PRIVATE)
                    .getBoolean("period_on_double_tap_$language", true)
            ) {
                val inputConnection = currentInputConnection ?: return
                inputConnection.deleteSurroundingText(1, 0)
                inputConnection.commitText(". ", 1)
            } else {
                val inputConnection = currentInputConnection ?: return
                inputConnection.deleteSurroundingText(1, 0)
                inputConnection.commitText("  ", 1)
            }
        }
    }

    protected fun switchToCommandToolBar() {
        val binding = KeyboardViewCommandOptionsBinding.inflate(layoutInflater)
        this.binding = binding
        val keyboardHolder = binding.root
        setupCommandBarTheme(binding)
        keyboardView = binding.keyboardView
        keyboardView!!.setKeyboard(keyboard!!)
        keyboardView!!.mOnKeyboardActionListener = this
        keyboardBinding.scribeKey.setOnClickListener {
            currentState = ScribeState.IDLE
            setupSelectCommandView()
            updateUI()
        }
        setInputView(keyboardHolder)
    }

    fun updateUI() {
        val sharedPref = getSharedPreferences("app_preferences", MODE_PRIVATE)
        val currentNightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val isSystemDarkMode = currentNightMode == Configuration.UI_MODE_NIGHT_YES
        val isUserDarkMode = sharedPref.getBoolean("dark_mode", isSystemDarkMode)
        when (currentState) {
            ScribeState.IDLE -> {
                setupIdleView()
                initializeEmojiButtons()
                updateButtonVisibility(isAutoSuggestEnabled)
                updateButtonText(isAutoSuggestEnabled, autosuggestEmojis)
            }
            ScribeState.SELECT_COMMAND -> setupSelectCommandView()
            else -> switchToToolBar()
        }
        updateEnterKeyColor(isUserDarkMode)
    }

    private fun switchToToolBar() {
        this.keyboardBinding = initializeKeyboardBinding()
        val keyboardHolder = keyboardBinding.root
        setupToolBarTheme(keyboardBinding)
        val sharedPref = getSharedPreferences("app_preferences", MODE_PRIVATE)
        val currentNightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val isSystemDarkMode = currentNightMode == Configuration.UI_MODE_NIGHT_YES
        val isUserDarkMode = sharedPref.getBoolean("dark_mode", isSystemDarkMode)
        when (isUserDarkMode) {
            true -> {
                keyboardBinding.topKeyboardDivider.setBackgroundColor(getColor(R.color.special_key_dark))
            }

            false -> {
                keyboardBinding.topKeyboardDivider.setBackgroundColor(getColor(R.color.special_key_light))
            }
        }
        handleModeChange(keyboardSymbols, keyboardView, this)
        keyboardView = keyboardBinding.keyboardView
        keyboardView!!.setKeyboard(keyboard!!)
        keyboardView!!.mOnKeyboardActionListener = this
        keyboardBinding.scribeKey.setOnClickListener {
            currentState = ScribeState.IDLE
            switchToCommandToolBar()
            updateUI()
        }
        setInputView(keyboardHolder)
        updateKeyboardMode(false)
    }

    private fun setupIdleView() {
        val sharedPref = getSharedPreferences("app_preferences", MODE_PRIVATE)
        val currentNightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val isSystemDarkMode = currentNightMode == Configuration.UI_MODE_NIGHT_YES
        val isUserDarkMode = sharedPref.getBoolean("dark_mode", isSystemDarkMode)
        when (isUserDarkMode) {
            true -> {
                binding.translateBtn.setBackgroundColor(getColor(R.color.transparent))
                binding.conjugateBtn.setBackgroundColor(getColor(R.color.transparent))
                binding.pluralBtn.setBackgroundColor(getColor(R.color.transparent))
                binding.translateBtn.setTextColor(Color.WHITE)
                binding.conjugateBtn.setTextColor(Color.WHITE)
                binding.pluralBtn.setTextColor(Color.WHITE)
                binding.separator2.setBackgroundColor(getColor(R.color.special_key_dark))
                binding.separator3.setBackgroundColor(getColor(R.color.special_key_dark))
                binding.separator4.setBackgroundColor(getColor(R.color.special_key_dark))
            }

            else -> {
                binding.translateBtn.setBackgroundColor(getColor(R.color.transparent))
                binding.conjugateBtn.setBackgroundColor(getColor(R.color.transparent))
                binding.pluralBtn.setBackgroundColor(getColor(R.color.transparent))
                binding.translateBtn.setTextColor(Color.BLACK)
                binding.conjugateBtn.setTextColor(Color.BLACK)
                binding.pluralBtn.setTextColor(Color.BLACK)
                binding.separator2.setBackgroundColor(getColor(R.color.special_key_light))
                binding.separator3.setBackgroundColor(getColor(R.color.special_key_light))
                binding.separator4.setBackgroundColor(getColor(R.color.special_key_light))
            }
        }

        setupCommandBarTheme(binding)
        binding.translateBtn.text = "Suggestion"
        binding.conjugateBtn.text = "Suggestion"
        binding.pluralBtn.text = "Suggestion"
        binding.separator2.visibility = View.VISIBLE
        binding.separator3.visibility = View.VISIBLE
        binding.scribeKey.setOnClickListener {
            currentState = ScribeState.SELECT_COMMAND
            updateButtonVisibility(false)
            Log.i("MY-TAG", "SELECT COMMAND STATE")
            binding.scribeKey.foreground = AppCompatResources.getDrawable(this, R.drawable.close)
            updateUI()
        }
    }

    val translatePlaceholder =
        mapOf(
            "EN" to ENInterfaceVariables.TRANSLATE_KEY_LBL,
            "ES" to ESInterfaceVariables.TRANSLATE_KEY_LBL,
            "DE" to DEInterfaceVariables.TRANSLATE_KEY_LBL,
            "IT" to ITInterfaceVariables.TRANSLATE_KEY_LBL,
            "FR" to FRInterfaceVariables.TRANSLATE_KEY_LBL,
            "PT" to PTInterfaceVariables.TRANSLATE_KEY_LBL,
            "RU" to RUInterfaceVariables.TRANSLATE_KEY_LBL,
            "SV" to SVInterfaceVariables.TRANSLATE_KEY_LBL,
        )

    val conjugatePlaceholder =
        mapOf(
            "EN" to ENInterfaceVariables.CONJUGATE_KEY_LBL,
            "ES" to ESInterfaceVariables.CONJUGATE_KEY_LBL,
            "DE" to DEInterfaceVariables.CONJUGATE_KEY_LBL,
            "IT" to ITInterfaceVariables.CONJUGATE_KEY_LBL,
            "FR" to FRInterfaceVariables.CONJUGATE_KEY_LBL,
            "PT" to PTInterfaceVariables.CONJUGATE_KEY_LBL,
            "RU" to RUInterfaceVariables.CONJUGATE_KEY_LBL,
            "SV" to SVInterfaceVariables.CONJUGATE_KEY_LBL,
        )

    val pluralPlaceholder =
        mapOf(
            "EN" to ENInterfaceVariables.PLURAL_KEY_LBL,
            "ES" to ESInterfaceVariables.PLURAL_KEY_LBL,
            "DE" to DEInterfaceVariables.PLURAL_KEY_LBL,
            "IT" to ITInterfaceVariables.PLURAL_KEY_LBL,
            "FR" to FRInterfaceVariables.PLURAL_KEY_LBL,
            "PT" to PTInterfaceVariables.PLURAL_KEY_LBL,
            "RU" to RUInterfaceVariables.PLURAL_KEY_LBL,
            "SV" to SVInterfaceVariables.PLURAL_KEY_LBL,
        )

    private fun setupSelectCommandView() {
        binding.translateBtn.background = AppCompatResources.getDrawable(this, R.drawable.button_background_rounded)
        binding.conjugateBtn.background = AppCompatResources.getDrawable(this, R.drawable.button_background_rounded)
        binding.pluralBtn.background = AppCompatResources.getDrawable(this, R.drawable.button_background_rounded)
        getLanguageAlias(language)
        binding.translateBtn.text = translatePlaceholder[getLanguageAlias(language)] ?: "Translate"
        binding.conjugateBtn.text = conjugatePlaceholder[getLanguageAlias(language)] ?: "Conjugate"
        binding.pluralBtn.text = pluralPlaceholder[getLanguageAlias(language)] ?: "Plural"
        binding.separator2.visibility = View.GONE
        binding.separator3.visibility = View.GONE
        setupCommandBarTheme(binding)
        binding.scribeKey.setOnClickListener {
            currentState = ScribeState.IDLE
            Log.i("MY-TAG", "IDLE STATE")
            binding.scribeKey.foreground = AppCompatResources.getDrawable(this, R.drawable.ic_scribe_icon_vector)
            updateUI()
        }
        binding.translateBtn.setOnClickListener {
            Log.i("MY-TAG", "TRANSLATE STATE")
            updateKeyboardMode(true)
            currentState = ScribeState.TRANSLATE
            updateUI()
        }
        binding.conjugateBtn.setOnClickListener {
            Log.i("MY-TAG", "CONJUGATE STATE")
            updateKeyboardMode(true)
            currentState = ScribeState.CONJUGATE
            updateUI()
        }
        binding.pluralBtn.setOnClickListener {
            Log.i("MY-TAG", "PLURAL STATE")
            updateKeyboardMode(true)
            currentState = ScribeState.PLURAL
            updateUI()
            if (language == "German") {
                keyboard!!.mShiftState = SHIFT_ON_ONE_CHAR
            }
        }
    }

    private fun initializeKeyboardBinding(): KeyboardViewKeyboardBinding {
        val keyboardBinding = KeyboardViewKeyboardBinding.inflate(layoutInflater)
        return keyboardBinding
    }

    override fun onInitializeInterface() {
        super.onInitializeInterface()
        keyboard = KeyboardBase(this, getKeyboardLayoutXML(), enterKeyType)
    }

    override fun hasTextBeforeCursor(): Boolean {
        val inputConnection = currentInputConnection ?: return false
        val textBeforeCursor = inputConnection.getTextBeforeCursor(Int.MAX_VALUE, 0)?.trim() ?: ""
        return textBeforeCursor.isNotEmpty() && textBeforeCursor.lastOrNull() != '.'
    }

    override fun onCreateInputView(): View {
        binding = KeyboardViewCommandOptionsBinding.inflate(layoutInflater)
        val keyboardHolder = binding.root
        keyboardView = binding.keyboardView
        keyboardView!!.setKeyboard(keyboard!!)
        keyboardView!!.setKeyboardHolder()
        keyboardView!!.mOnKeyboardActionListener = this
        return keyboardHolder
    }

    fun initializeEmojiButtons() {
        pluralBtn = binding.pluralBtn
        emojiBtnPhone1 = binding.emojiBtnPhone1
        emojiSpacePhone = binding.emojiSpacePhone
        emojiBtnPhone2 = binding.emojiBtnPhone2
        emojiBtnTablet1 = binding.emojiBtnTablet1
        emojiSpaceTablet1 = binding.emojiSpaceTablet1
        emojiBtnTablet2 = binding.emojiBtnTablet2
        emojiSpaceTablet2 = binding.emojiSpaceTablet2
        emojiBtnTablet3 = binding.emojiBtnTablet3
        genderSuggestionLeft = binding.translateBtnLeft
        genderSuggestionRight = binding.translateBtnRight
    }

    fun updateButtonVisibility(isAutoSuggestEnabled: Boolean) {
        val isTablet =
            (
                resources.configuration.screenLayout and
                    Configuration.SCREENLAYOUT_SIZE_MASK
            ) >= Configuration.SCREENLAYOUT_SIZE_LARGE
        if (isTablet) {
            pluralBtn?.visibility = if (isAutoSuggestEnabled) View.INVISIBLE else View.VISIBLE
            emojiBtnTablet1?.visibility = if (isAutoSuggestEnabled) View.VISIBLE else View.INVISIBLE
            emojiSpaceTablet1?.visibility = if (isAutoSuggestEnabled) View.VISIBLE else View.INVISIBLE
            emojiBtnTablet2?.visibility = if (isAutoSuggestEnabled) View.VISIBLE else View.INVISIBLE
            emojiSpaceTablet2?.visibility = if (isAutoSuggestEnabled) View.VISIBLE else View.INVISIBLE
            emojiBtnTablet3?.visibility = if (isAutoSuggestEnabled) View.VISIBLE else View.INVISIBLE
        } else {
            pluralBtn?.visibility = if (isAutoSuggestEnabled) View.INVISIBLE else View.VISIBLE
            emojiBtnPhone1?.visibility = if (isAutoSuggestEnabled) View.VISIBLE else View.INVISIBLE
            emojiSpacePhone?.visibility = if (isAutoSuggestEnabled) View.VISIBLE else View.INVISIBLE
            emojiBtnPhone2?.visibility = if (isAutoSuggestEnabled) View.VISIBLE else View.INVISIBLE
        }
    }

    fun getText(): String? {
        val inputConnection = currentInputConnection ?: return null
        return inputConnection.getTextBeforeCursor(TEXT_LENGTH, 0)?.toString()
    }

    fun getLastWordBeforeCursor(): String? {
        val textBeforeCursor = getText() ?: return null
        val trimmedText = textBeforeCursor.trim()
        val lastWord = trimmedText.split("\\s+".toRegex()).lastOrNull()
        return lastWord
    }

    fun findEmojisForLastWord(
        emojiKeywords: HashMap<String, MutableList<String>>,
        lastWord: String?,
    ): MutableList<String>? {
        lastWord?.let { word ->
            val lowerCaseWord = word.lowercase()
            val emojis = emojiKeywords[lowerCaseWord]
            if (emojis != null) {
                Log.d("Debug", "Emojis for '$word': $emojis")
                return emojis
            } else {
                Log.d("Debug", "No emojis found for '$word'")
            }
        }
        return null
    }

    fun findGenderForLastWord(
        nounKeywords: HashMap<String, List<String>>,
        lastWord: String?,
    ): List<String>? {
        lastWord?.let { word ->
            val lowerCaseWord = word.lowercase()
            Log.i("MY-TAG", word)
            Log.i("MY-TAG", nounKeywords.keys.toString())
            Log.i("MY-TAG", nounKeywords[word].toString())
            val gender = nounKeywords[lowerCaseWord]
            if (gender != null) {
                Log.d("Debug", "Gender for '$word': $gender")
                Log.i("MY-TAG", pluralWords.contains(lastWord).toString())
                if (pluralWords.any { it.equals(lastWord, ignoreCase = true) }) {
                    Log.i("MY-TAG", "Plural Words : $pluralWords")
                    isSingularAndPlural = true
                    Log.i("MY-TAG", "isSingularPlural Updated to true")
                } else {
                    isSingularAndPlural = false
                    Log.i("MY-TAG", "Plural Words : $pluralWords")
                    Log.i("MY-TAG", "isSingularPlural Updated to false")
                }
                return gender
            } else {
                Log.d("Debug", "No gender found for '$word'")
            }
        }
        return null
    }

    fun findWheatherWordIsPlural(
        pluralWords: List<String>,
        lastWord: String?,
    ): Boolean {
        for (item in pluralWords) {
            if (item == lastWord) {
                return true
            }
        }
        return false
    }

    fun getCaseAnnotationForPreposition(
        caseAnnotation: HashMap<String, MutableList<String>>,
        lastWord: String?,
    ): MutableList<String>? {
        lastWord?.let { word ->
            val lowerCaseWord = word.lowercase()
            val caseAnnotations = caseAnnotation[lowerCaseWord]
            return caseAnnotations
        }
        return null
    }

    fun updateButtonText(
        isAutoSuggestEnabled: Boolean,
        autosuggestEmojis: MutableList<String>?,
    ) {
        if (isAutoSuggestEnabled) {
            emojiBtnTablet1?.text = autosuggestEmojis?.get(0)
            emojiBtnTablet2?.text = autosuggestEmojis?.get(1)
            emojiBtnTablet3?.text = autosuggestEmojis?.get(2)

            emojiBtnPhone1?.text = autosuggestEmojis?.get(0)
            emojiBtnPhone2?.text = autosuggestEmojis?.get(1)

            binding.emojiBtnTablet1.setOnClickListener { insertEmoji(emojiBtnTablet1?.text.toString()) }
            binding.emojiBtnTablet2.setOnClickListener { insertEmoji(emojiBtnTablet2?.text.toString()) }
            binding.emojiBtnTablet3.setOnClickListener { insertEmoji(emojiBtnTablet3?.text.toString()) }

            binding.emojiBtnPhone1.setOnClickListener { insertEmoji(emojiBtnPhone1?.text.toString()) }
            binding.emojiBtnPhone2.setOnClickListener { insertEmoji(emojiBtnPhone2?.text.toString()) }
        }
    }

    fun updateAutoSuggestText(
        nounTypeSuggestion: List<String>? = null,
        isPlural: Boolean = false,
        caseAnnotationSuggestion: MutableList<String>? = null,
    ) {
        if (isPlural) {
            handlePluralAutoSuggest()
        } else {
            nounTypeSuggestion?.size?.let {
                if (it > 1 || isSingularAndPlural) {
                    handleMultipleNounFormats(nounTypeSuggestion, "noun")
                } else {
                    handleSingleType(nounTypeSuggestion, "noun")
                }
            }
            caseAnnotationSuggestion?.size?.let {
                if (it > 1) {
                    handleMultipleNounFormats(caseAnnotationSuggestion, "preposition")
                } else {
                    handleSingleType(caseAnnotationSuggestion, "preposition")
                }
            }
        }
    }

    fun handlePluralAutoSuggest() {
        var(colorRes, text) = handleColorAndTextForNounType(nounType = "PL")
        text = "PL"
        colorRes = R.color.annotateOrange
        binding.translateBtnLeft.visibility = View.INVISIBLE
        binding.translateBtnRight.visibility = View.INVISIBLE
        binding.translateBtn.apply {
            visibility = View.VISIBLE
            binding.translateBtn.text = text
            textSize = NOUN_TYPE_SIZE
            background =
                ContextCompat.getDrawable(context, R.drawable.rounded_drawable)?.apply {
                    setTintMode(PorterDuff.Mode.SRC_IN)
                    setTint(ContextCompat.getColor(context, colorRes))
                }
        }
    }

    fun handleSingleType(
        singleTypeSuggestion: List<String>?,
        type: String? = null,
    ) {
        val text = singleTypeSuggestion?.get(0).toString()
        var (colorRes, buttonText) = Pair(R.color.transparent, "Suggestion")
        val sharedPref = getSharedPreferences("app_preferences", MODE_PRIVATE)
        val currentNightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val isSystemDarkMode = currentNightMode == Configuration.UI_MODE_NIGHT_YES
        val isUserDarkMode = sharedPref.getBoolean("dark_mode", isSystemDarkMode)
        var textColor = md_grey_black_dark
        if (isUserDarkMode) {
            colorRes = white
            textColor = md_grey_black_dark
        } else {
            colorRes = md_grey_black_dark
            textColor = white
        }
        when (type) {
            "noun" -> {
                val (newColorRes, newButtonText) = handleColorAndTextForNounType(text)
                colorRes = newColorRes
                buttonText = newButtonText
            }
            "preposition" -> {
                val (newColorRes, newButtonText) = handleTextForCaseAnnotation(text)
                buttonText = newButtonText
                textColor = textColor
            }
            else -> {
                val (newColorRes, newButtonText) = Pair(R.color.transparent, "Suggestion")
                colorRes = newColorRes
                buttonText = newButtonText
            }
        }
        Log.i("MY-TAG", "These are the colorRes and text $colorRes and $buttonText")
        binding.translateBtnLeft.visibility = View.INVISIBLE
        binding.translateBtnRight.visibility = View.INVISIBLE
        binding.translateBtn.setTextColor(getColor(textColor))
        binding.translateBtn.apply {
            visibility = View.VISIBLE
            binding.translateBtn.text = buttonText
            textSize = NOUN_TYPE_SIZE
            setTextColor(getColor(textColor))
            background =
                ContextCompat.getDrawable(context, R.drawable.rounded_drawable)?.apply {
                    setTintMode(PorterDuff.Mode.SRC_IN)
                    setTint(ContextCompat.getColor(context, colorRes))
                }
        }
    }

    fun handleMultipleNounFormats(
        multipleTypeSuggestion: List<String>?,
        type: String? = null,
    ) {
        binding.apply {
            translateBtnLeft.visibility = View.VISIBLE
            translateBtnRight.visibility = View.VISIBLE
            translateBtn.visibility = View.INVISIBLE
            binding.translateBtnLeft.setTextColor(getColor(white))
            binding.translateBtnRight.setTextColor(getColor(white))
            val (leftType, rightType) =
                if (isSingularAndPlural) {
                    "PL" to multipleTypeSuggestion?.get(0).toString()
                } else {
                    multipleTypeSuggestion?.get(0).toString() to multipleTypeSuggestion?.get(1).toString()
                }

            when (type) {
                "noun" -> {
                    handleTextForNouns(leftType, rightType, binding)
                }
                "preposition" -> {
                    handleTextForPreposition(leftType, rightType, binding)
                }
            }
        }
    }

    fun handleTextForNouns(
        leftType: String,
        rightType: String,
        binding: KeyboardViewCommandOptionsBinding,
    ) {
        handleColorAndTextForNounType(leftType).let { (colorRes, text) ->
            binding.translateBtnLeft.text = text
            binding.translateBtnLeft.background =
                ContextCompat
                    .getDrawable(
                        applicationContext,
                        R.drawable.gender_suggestion_button_left_background,
                    )?.apply {
                        setTintMode(PorterDuff.Mode.SRC_IN)
                        setTint(ContextCompat.getColor(applicationContext, colorRes))
                    }
        }

        handleColorAndTextForNounType(rightType).let { (colorRes, text) ->
            binding.translateBtnRight.text = text
            binding.translateBtnRight.background =
                ContextCompat
                    .getDrawable(
                        applicationContext,
                        R.drawable.gender_suggestion_button_right_background,
                    )?.apply {
                        setTintMode(PorterDuff.Mode.SRC_IN)
                        setTint(ContextCompat.getColor(applicationContext, colorRes))
                    }
        }
    }

    fun handleTextForPreposition(
        leftType: String,
        rightType: String,
        binding: KeyboardViewCommandOptionsBinding,
    ) {
        handleTextForCaseAnnotation(leftType).let { (colorRes, text) ->
            binding.translateBtnLeft.text = text
            binding.translateBtnLeft.background =
                ContextCompat
                    .getDrawable(
                        applicationContext,
                        R.drawable.gender_suggestion_button_left_background,
                    )?.apply {
                        setTintMode(PorterDuff.Mode.SRC_IN)
                        setTint(ContextCompat.getColor(applicationContext, colorRes))
                    }
        }

        handleTextForCaseAnnotation(rightType).let { (colorRes, text) ->
            binding.translateBtnRight.text = text
            binding.translateBtnRight.background =
                ContextCompat
                    .getDrawable(
                        applicationContext,
                        R.drawable.gender_suggestion_button_right_background,
                    )?.apply {
                        setTintMode(PorterDuff.Mode.SRC_IN)
                        setTint(ContextCompat.getColor(applicationContext, colorRes))
                    }
        }
    }

    fun handleTextForCaseAnnotation(nounType: String): Pair<Int, String> {
        val suggestionMap =
            mapOf(
                "genitive case" to Pair(md_grey_black_dark, processValuesForPreposition(language, "Gen")),
                "accusative case" to Pair(md_grey_black_dark, processValuesForPreposition(language, "Acc")),
                "dative case" to Pair(md_grey_black_dark, processValuesForPreposition(language, "Dat")),
                "locative case" to Pair(md_grey_black_dark, processValuesForPreposition(language, "Loc")),
                "Prepositional case" to Pair(md_grey_black_dark, processValuesForPreposition(language, "Pre")),
                "Instrumental case" to Pair(md_grey_black_dark, processValuesForPreposition(language, "Ins")),
            )
        var (colorRes, text) =
            suggestionMap[nounType]
                ?: Pair(R.color.transparent, "Suggestion")
        return Pair(colorRes, text)
    }

    fun handleColorAndTextForNounType(nounType: String): Pair<Int, String> {
        Log.i("MY-TAG", "Hi i am from handleColorAndText for npun type and the langauge is $language")
        val suggestionMap =
            mapOf(
                "PL" to Pair(R.color.annotateOrange, "PL"),
                "neuter" to Pair(R.color.annotateGreen, "N"),
                "common of two genders" to Pair(R.color.annotatePurple, processValueForNouns(language, "C")),
                "common" to Pair(R.color.annotatePurple, processValueForNouns(language, "C")),
                "masculine" to Pair(R.color.annotateBlue, processValueForNouns(language, "M")),
                "feminine" to Pair(R.color.annotateRed, processValueForNouns(language, "F")),
            )

        var (colorRes, text) =
            suggestionMap[nounType]
                ?: Pair(R.color.transparent, "Suggestion")
        return Pair(colorRes, text)
    }

    fun processValueForNouns(
        language: String,
        text: String,
    ): String {
        var textOutput: String
        if (nounAnnotationConversionDict[language]?.get(text) != null) {
            textOutput = nounAnnotationConversionDict[language]?.get(text).toString()
        } else {
            return text
        }
        return textOutput
    }

    fun processValuesForPreposition(
        language: String,
        text: String,
    ): String {
        var textOutput: String
        if (prepAnnotationConversionDict[language]?.get(text) != null) {
            textOutput = prepAnnotationConversionDict[language]?.get(text).toString()
        } else {
            return text
        }
        return textOutput
    }

    fun disableAutoSuggest() {
        binding.translateBtnRight.visibility = View.INVISIBLE
        binding.translateBtnLeft.visibility = View.INVISIBLE
        binding.translateBtn.visibility = View.VISIBLE
        binding.translateBtn.text = "Suggestion"
        binding.translateBtn.setTextColor(getColor(R.color.special_key_dark))
        binding.translateBtn.setBackgroundColor(getColor(R.color.transparent))
        binding.translateBtn.textSize = SUGGESTION_SIZE
    }

    private fun insertEmoji(emoji: String) {
        val inputConnection = currentInputConnection ?: return
        inputConnection.commitText(emoji, 1)
    }

    private fun getPluralRepresentation(word: String?): String? {
        if (word.isNullOrEmpty()) return null
        val languageAlias = getLanguageAlias(language)
        val pluralRepresentationMap = dbHelper.getPluralRepresentation(languageAlias, word)
        return pluralRepresentationMap.values.filterNotNull().firstOrNull()
    }

    override fun onPress(primaryCode: Int) {
        if (primaryCode != 0) {
            keyboardView?.vibrateIfNeeded()
        }
    }

    override fun onStartInput(
        attribute: EditorInfo?,
        restarting: Boolean,
    ) {
        super.onStartInput(attribute, restarting)

        inputTypeClass = attribute!!.inputType and TYPE_MASK_CLASS
        enterKeyType = attribute.imeOptions and (IME_MASK_ACTION or IME_FLAG_NO_ENTER_ACTION)
        currentEnterKeyType = enterKeyType
        val inputConnection = currentInputConnection
        hasTextBeforeCursor = inputConnection?.getTextBeforeCursor(1, 0)?.isNotEmpty() == true

        val keyboardXml =
            when (inputTypeClass) {
                TYPE_CLASS_NUMBER, TYPE_CLASS_DATETIME, TYPE_CLASS_PHONE -> {
                    keyboardMode = keyboardSymbols
                    R.xml.keys_symbols
                }

                else -> {
                    keyboardMode = keyboardLetters
                    getKeyboardLayoutXML()
                }
            }

        val languageAlias = getLanguageAlias(language)
        dbHelper = DatabaseHelper(this)
        dbHelper.loadDatabase(languageAlias)
        emojiKeywords = dbHelper.getEmojiKeywords(languageAlias)
        pluralWords = dbHelper.checkIfWordIsPlural(languageAlias)!!
        nounKeywords = dbHelper.findGenderOfWord(languageAlias)

        caseAnnotation = dbHelper.findCaseAnnnotationForPreposition(languageAlias)

        Log.i("MY-TAG", nounKeywords.toString())
        keyboard = KeyboardBase(this, keyboardXml, enterKeyType)
        keyboardView?.setKeyboard(keyboard!!)
    }

    private fun getLanguageAlias(language: String): String =
        when (language) {
            "English" -> "EN"
            "French" -> "FR"
            "German" -> "DE"
            "Italian" -> "IT"
            "Portuguese" -> "PT"
            "Russian" -> "RU"
            "Spanish" -> "ES"
            "Swedish" -> "SV"
            else -> ""
        }

    fun updateShiftKeyState() {
        // The shift state in the Scribe commands should not depend on the Input Connection.
        // The current state should be transferred to the command unless required by the language.
        if ((currentState == ScribeState.IDLE || currentState == ScribeState.SELECT_COMMAND) &&
            keyboardMode == keyboardLetters
        ) {
            val editorInfo = currentInputEditorInfo
            if (
                editorInfo != null &&
                editorInfo.inputType != InputType.TYPE_NULL &&
                keyboard?.mShiftState != SHIFT_ON_PERMANENT
            ) {
                if (currentInputConnection.getCursorCapsMode(editorInfo.inputType) != 0) {
                    keyboard?.setShifted(SHIFT_ON_ONE_CHAR)
                    keyboardView?.invalidateAllKeys()
                }
            }
        }
    }

    override fun onActionUp() {
        if (switchToLetters) {
            keyboardMode = keyboardLetters
            keyboard = KeyboardBase(this, getKeyboardLayoutXML(), enterKeyType)

            val editorInfo = currentInputEditorInfo
            if (
                editorInfo != null &&
                editorInfo.inputType != InputType.TYPE_NULL &&
                keyboard?.mShiftState != SHIFT_ON_PERMANENT
            ) {
                if (currentInputConnection.getCursorCapsMode(editorInfo.inputType) != 0) {
                    keyboard?.setShifted(SHIFT_ON_ONE_CHAR)
                }
            }

            keyboardView!!.setKeyboard(keyboard!!)
            switchToLetters = false
        }
    }

    override fun moveCursorLeft() {
        moveCursor(false)
    }

    override fun moveCursorRight() {
        moveCursor(true)
    }

    override fun onText(text: String) {
        currentInputConnection?.commitText(text, 0)
    }

    private fun moveCursor(moveRight: Boolean) {
        val extractedText = currentInputConnection?.getExtractedText(ExtractedTextRequest(), 0) ?: return
        var newCursorPosition = extractedText.selectionStart
        newCursorPosition =
            if (moveRight) {
                newCursorPosition + 1
            } else {
                newCursorPosition - 1
            }

        currentInputConnection?.setSelection(newCursorPosition, newCursorPosition)
    }

    private fun getImeOptionsActionId(): Int =
        if (currentInputEditorInfo.imeOptions and IME_FLAG_NO_ENTER_ACTION != 0) {
            IME_ACTION_NONE
        } else {
            currentInputEditorInfo.imeOptions and IME_MASK_ACTION
        }

    fun handleKeycodeEnter(
        binding: KeyboardViewKeyboardBinding? = null,
        commandBarState: Boolean? = false,
    ) {
        val inputConnection = currentInputConnection
        val imeOptionsActionId = getImeOptionsActionId()

        if (commandBarState == true) {
            val commandBarInput =
                binding
                    ?.commandBar
                    ?.text
                    .toString()
                    .trim()
                    .dropLast(1)
            lateinit var commandModeOutput: String
            when (currentState) {
                ScribeState.PLURAL -> commandModeOutput = getPluralRepresentation(commandBarInput) ?: ""
                else -> commandModeOutput = commandBarInput
            }
            if (commandModeOutput.length > commandBarInput.length) {
                commandModeOutput = "$commandModeOutput "
            }
            inputConnection.commitText(commandModeOutput, 1)
            binding?.commandBar?.text = ""
        } else {
            if (imeOptionsActionId != IME_ACTION_NONE) {
                inputConnection.performEditorAction(imeOptionsActionId)
            } else {
                inputConnection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                inputConnection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
            }
        }
    }

    fun handleModeChange(
        keyboardMode: Int,
        keyboardView: KeyboardView?,
        context: Context,
    ) {
        val keyboardXml =
            if (keyboardMode == keyboardLetters) {
                this.keyboardMode = keyboardSymbols
                R.xml.keys_symbols
            } else {
                this.keyboardMode = keyboardLetters
                getKeyboardLayoutXML()
            }
        keyboard = KeyboardBase(context, keyboardXml, enterKeyType)
        keyboardView?.invalidateAllKeys()
        keyboardView?.setKeyboard(keyboard!!)
    }

    fun handleKeyboardLetters(
        keyboardMode: Int,
        keyboardView: KeyboardView?,
    ) {
        if (keyboardMode == keyboardLetters) {
            when {
                keyboard!!.mShiftState == SHIFT_ON_PERMANENT -> {
                    keyboard!!.mShiftState = SHIFT_OFF
                }
                System.currentTimeMillis() - lastShiftPressTS < shiftPermToggleSpeed -> {
                    keyboard!!.mShiftState = SHIFT_ON_PERMANENT
                }
                keyboard!!.mShiftState == SHIFT_ON_ONE_CHAR -> {
                    keyboard!!.mShiftState = SHIFT_OFF
                }
                keyboard!!.mShiftState == SHIFT_OFF -> {
                    keyboard!!.mShiftState = SHIFT_ON_ONE_CHAR
                }
            }

            lastShiftPressTS = System.currentTimeMillis()
        } else {
            val keyboardXml =
                if (keyboardMode == keyboardSymbols) {
                    this.keyboardMode = keyboardSymbolShift
                    R.xml.keys_symbols_shift
                } else {
                    this.keyboardMode = keyboardSymbols
                    R.xml.keys_symbols
                }
            keyboard = KeyboardBase(this, keyboardXml, enterKeyType)
            keyboardView!!.setKeyboard(keyboard!!)
        }
    }

    private fun handleCommandBarDelete(binding: KeyboardViewKeyboardBinding?) {
        binding?.commandBar?.let { commandBar ->
            var newText = ""
            if (commandBar.text.length <= 2) {
                binding.promptTextBorder?.visibility = View.VISIBLE
                binding.commandBar.setPadding(
                    binding.commandBar.paddingRight,
                    binding.commandBar.paddingTop,
                    binding.commandBar.paddingRight,
                    binding.commandBar.paddingBottom,
                )
                if (language == "German" && this.currentState == ScribeState.PLURAL) {
                    keyboard?.mShiftState = SHIFT_ON_ONE_CHAR
                }
            } else {
                newText = "${commandBar.text.trim().dropLast(2)}$commandChar"
            }
            commandBar.text = newText
        }
    }

    fun handleDelete(
        currentState: Boolean? = false,
        binding: KeyboardViewKeyboardBinding? = null,
    ) {
        val wordBeforeCursor = getText()
        val inputConnection = currentInputConnection
        if (keyboard!!.mShiftState == SHIFT_ON_ONE_CHAR) {
            keyboard!!.mShiftState = SHIFT_OFF
        }

        if (currentState == true) {
            handleCommandBarDelete(binding)
        } else {
            val selectedText = inputConnection.getSelectedText(0)
            if (TextUtils.isEmpty(selectedText)) {
                if (isEmoji(wordBeforeCursor)) {
                    inputConnection.deleteSurroundingText(2, 0)
                } else {
                    inputConnection.deleteSurroundingText(1, 0)
                }
            } else {
                inputConnection.commitText("", 1)
            }
        }
    }

    private fun isEmoji(word: String?): Boolean {
        if (word.isNullOrEmpty() || word.length < 2) {
            return false
        }

        val lastTwoChars = word.substring(word.length - 2)
        val emojiRegex = Regex("[\\uD83C\\uDF00-\\uD83E\\uDDFF]|[\\u2600-\\u26FF]|[\\u2700-\\u27BF]")
        return emojiRegex.containsMatchIn(lastTwoChars)
    }

    fun handleElseCondition(
        code: Int,
        keyboardMode: Int,
        binding: KeyboardViewKeyboardBinding?,
        commandBarState: Boolean = false,
    ) {
        val inputConnection = currentInputConnection ?: return
        var codeChar = code.toChar()

        if (Character.isLetter(codeChar) && keyboard!!.mShiftState > SHIFT_OFF) {
            codeChar = Character.toUpperCase(codeChar)
        }

        if (commandBarState) {
            binding?.commandBar?.let { commandBar ->
                if (commandBar.text.isEmpty()) {
                    binding.promptTextBorder?.visibility = View.GONE
                    binding.commandBar.setPadding(
                        0,
                        binding.commandBar.paddingTop,
                        binding.commandBar.paddingRight,
                        binding.commandBar.paddingBottom,
                    )
                }
                val newText = "${commandBar.text.trim().dropLast(1)}$codeChar$commandChar"
                commandBar.text = newText
            }
        } else {
            // Handling space key logic.
            if (keyboardMode != keyboardLetters && code == KeyboardBase.KEYCODE_SPACE) {
                binding?.commandBar?.text = " "
                val originalText = inputConnection.getExtractedText(ExtractedTextRequest(), 0).text
                inputConnection.commitText(codeChar.toString(), 1)
                val newText = inputConnection.getExtractedText(ExtractedTextRequest(), 0).text
                switchToLetters = originalText != newText
            } else {
                binding?.commandBar?.append(codeChar.toString())
                inputConnection.commitText(codeChar.toString(), 1)
            }
        }

        if (keyboard!!.mShiftState == SHIFT_ON_ONE_CHAR && keyboardMode == keyboardLetters) {
            keyboard!!.mShiftState = SHIFT_OFF
            keyboardView!!.invalidateAllKeys()
        }
    }

    override fun onStartInputView(
        editorInfo: EditorInfo?,
        restarting: Boolean,
    ) {
        val sharedPref = getSharedPreferences("app_preferences", MODE_PRIVATE)
        val currentNightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val isSystemDarkMode = currentNightMode == Configuration.UI_MODE_NIGHT_YES
        val isUserDarkMode = sharedPref.getBoolean("dark_mode", isSystemDarkMode)
        updateEnterKeyColor(isUserDarkMode)
        initializeEmojiButtons()
        isAutoSuggestEnabled = sharedPref.getBoolean("emoji_suggestions_$language", true)
        updateButtonVisibility(isAutoSuggestEnabled)
        setupIdleView()
        super.onStartInputView(editorInfo, restarting)
        setupCommandBarTheme(binding)
    }

    private fun setupToolBarTheme(binding: KeyboardViewKeyboardBinding) {
        val sharedPref = getSharedPreferences("app_preferences", MODE_PRIVATE)
        val currentNightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val isSystemDarkMode = currentNightMode == Configuration.UI_MODE_NIGHT_YES
        val isUserDarkMode = sharedPref.getBoolean("dark_mode", isSystemDarkMode)
        when (isUserDarkMode) {
            true -> {
                binding.commandField.setBackgroundColor(getColor(md_grey_black_dark))
            }

            else -> {
                binding.commandField.setBackgroundColor(getColor(R.color.light_cmd_bar_border_color))
            }
        }
    }

    fun setupCommandBarTheme(binding: KeyboardViewCommandOptionsBinding) {
        val sharedPref = getSharedPreferences("app_preferences", MODE_PRIVATE)
        val currentNightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val isSystemDarkMode = currentNightMode == Configuration.UI_MODE_NIGHT_YES
        val isUserDarkMode = sharedPref.getBoolean("dark_mode", isSystemDarkMode)
        when (isUserDarkMode) {
            true -> {
                binding.commandField.setBackgroundColor(getColor(md_grey_black_dark))
            }

            else -> {
                binding.commandField.setBackgroundColor(getColor(R.color.light_cmd_bar_border_color))
            }
        }
    }

    private companion object {
        const val DEFAULT_SHIFT_PERM_TOGGLE_SPEED = 500
        const val TEXT_LENGTH = 20
        const val NOUN_TYPE_SIZE = 25f
        const val SUGGESTION_SIZE = 15f
    }
}
