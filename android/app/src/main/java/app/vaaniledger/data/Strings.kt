package app.vaaniledger.data

/**
 * User-facing text, in the two languages [AppLanguage] offers.
 *
 * ONE RULE DECIDES WHAT "HI" MEANS HERE: it is Hinglish, the register the
 * app has always used ("Bubble chalu karein"), never literary Hindi. "EN" is
 * asked for by name and gets full English -- not a second flavour of
 * Hinglish with different words. Mixing the two within one language reads as
 * broken to whichever audience did not ask for it.
 *
 * Covers the screens a shopkeeper actually lives in day to day: onboarding,
 * the setup screen, the bubble panel, and the review queue -- plus every
 * toast on the capture and import paths, since a shopkeeper who cannot tell
 * why a scan failed cannot fix it. `ConfirmSheetView`, `EditSheetView` and
 * `KhataView` are deliberately NOT covered yet; they stay Hinglish-only
 * until localized in a follow-up, which is a scope cut, not an oversight.
 */
object Strings {

    fun of(language: AppLanguage): Bundle = if (language == AppLanguage.EN) EN else HI

    data class Bundle(
        val onboarding: Onboarding,
        val main: Main,
        val panel: Panel,
        val review: Review,
        val capture: Capture,
    )

    data class Onboarding(
        val welcomeTitle: String,
        val welcomeSubtitle: String,
        val welcomeStart: String,
        val categoryTitle: String,
        val categorySubtitle: String,
        val languageTitle: String,
        val languageSubtitle: String,
        val next: String,
        val back: String,
        val finish: String,
        val stepOf: (Int, Int) -> String,
    )

    data class Main(
        val appTitle: String,
        val tagline: String,
        val overlayTitle: String,
        val overlayDesc: String,
        val accessibilityTitle: String,
        val accessibilityDesc: String,
        val statusOn: String,
        val statusOffPrefix: String,
        val bubbleStart: String,
        val bubbleStop: String,
        val needPermissionFirst: String,
        val helperText: String,
        val waysHeading: String,
        val openWhatsAppTitle: String,
        val openWhatsAppDesc: String,
        val importTitle: String,
        val importDesc: String,
        val settingsTitle: String,
        val toastNoOverlayFirst: String,
        val toastWhatsAppNotFound: String,
        val toastOpenChatThenBubble: String,
        val toastNoFilePicker: String,
    )

    data class Panel(
        val statLabelSale: String,
        val statLabelCash: String,
        val statLabelCredit: String,
        val noEntriesToday: String,
        val readChat: String,
        val readChatSub: String,
        val speak: String,
        val speakSub: String,
        val ledger: String,
        val ledgerSub: String,
    )

    data class Review(
        val allDone: String,
        val newOrder: (Int) -> String,
        val approveAll: String,
        val approveAllSub: (Int) -> String,
        val emptyTitle: String,
        val emptySubtitle: String,
        val close: String,
        val save: String,
        val saveSub: String,
        val skip: String,
        val skipSub: String,
        val newCustomer: String,
        val fromConversation: String,
        val noPriceYet: String,
        val toastSaved: (String) -> String,
        val toastSavedNoAmount: String,
        val toastSkipped: String,
        val toastSaveFailed: String,
        val toastApprovedCount: (Int) -> String,
        val modeCredit: String,
        val modeCash: String,
        val modeOnline: String,
    )

    data class Capture(
        val accessibilityOff: String,
        val openWhatsAppFirst: String,
        val reading: String,
        val noMessagesFound: String,
        val noOrderFound: String,
        val offlineFallback: String,
        val offlineParsed: String,
        val importEmptyFile: String,
        val importReading: String,
        val importNotAnExport: String,
        val importCustomerUnknown: String,
        val importServerRejected: (Int) -> String,
        val importServerUnreachable: String,
        val importFailed: String,
    )

    // ------------------------------------------------------------------
    // Hinglish
    // ------------------------------------------------------------------

    private val HI = Bundle(
        onboarding = Onboarding(
            welcomeTitle = "VaaniLedger mein swagat hai",
            welcomeSubtitle = "Aapka khata, aapki awaaz mein. Shuru karne se pehle do chhoti si baatein poochni hain.",
            welcomeStart = "Shuru karein",
            categoryTitle = "Aapki dukaan kis cheez ki hai?",
            categorySubtitle = "Isse app ko pata chalega ki aap kya bechte hain, taaki order sahi se padh sake.",
            languageTitle = "App kis bhasha mein chale?",
            languageSubtitle = "Aap baad mein settings se badal sakte hain.",
            next = "Aage",
            back = "Peeche",
            finish = "Ho gaya",
            stepOf = { step, total -> "Step $step / $total" },
        ),
        main = Main(
            appTitle = "VaaniLedger",
            tagline = "Aapka khata, aapki awaaz mein",
            overlayTitle = "Screen ke upar dikhana",
            overlayDesc = "Bubble ko WhatsApp ke upar aane deta hai. Iske bina app kaam nahi karega.",
            accessibilityTitle = "Chat padhne ki ijaazat",
            accessibilityDesc = "Sirf WhatsApp. Sirf jab aap Capture dabate hain. Background mein kabhi nahi.",
            statusOn = "CHALU HAI",
            statusOffPrefix = "BAND HAI — DABA KAR CHALU KAREIN",
            bubbleStart = "Bubble chalu karein",
            bubbleStop = "Bubble band karein",
            needPermissionFirst = "Pehle permission dijiye",
            helperText = "Bubble chalu karne ke baad WhatsApp kholiye — bubble upar dikhega.",
            waysHeading = "ORDER PADHNE KE DO TAREEQE",
            openWhatsAppTitle = "WhatsApp kholein",
            openWhatsAppDesc = "Chat screening — jo chat khuli hai usse order padhein",
            importTitle = "Export file se import karein",
            importDesc = "Poori chat ka record — WhatsApp se export ki hui file chuniye",
            settingsTitle = "Settings",
            toastNoOverlayFirst = "Pehle 'screen ke upar dikhana' chalu kijiye",
            toastWhatsAppNotFound = "WhatsApp is phone par nahi mila",
            toastOpenChatThenBubble = "Chat kholiye, phir bubble dabaiye",
            toastNoFilePicker = "File chunne ka app nahi mila",
        ),
        panel = Panel(
            statLabelSale = "SALE",
            statLabelCash = "CASH",
            statLabelCredit = "UDHAAR",
            noEntriesToday = "Aaj koi entry nahi",
            readChat = "Yeh likh lo",
            readChatSub = "read this chat",
            speak = "Bol ke likho",
            speakSub = "voice",
            ledger = "Khata",
            ledgerSub = "all customers",
        ),
        review = Review(
            allDone = "Sab ho gaya",
            newOrder = { n -> if (n == 1) "1 naya order" else "$n naye order" },
            approveAll = "Sab sahi hai",
            approveAllSub = { n -> "approve all $n" },
            emptyTitle = "Koi naya order nahi mila.",
            emptySubtitle = "Jo pehle likh diye gaye the, wo dobara nahi aayenge — lekin jo aapne 'Nahi' kaha tha, wo phir dikhenge.",
            close = "Band karein",
            save = "Likh lo",
            saveSub = "save to khata",
            skip = "Nahi",
            skipSub = "abhi nahi",
            newCustomer = "NAYA GRAHAK",
            fromConversation = "BAAT-CHEET SE",
            noPriceYet = "DAAM NAHI MILA",
            toastSaved = { amount -> "$amount khaate mein likh diya" },
            toastSavedNoAmount = "Entry likh di",
            toastSkipped = "Chhod diya — baad mein dobara dikhega",
            toastSaveFailed = "Save nahi hua",
            toastApprovedCount = { n -> "$n entry likh di" },
            modeCredit = "UDHAAR",
            modeCash = "NAKAD",
            modeOnline = "ONLINE",
        ),
        capture = Capture(
            accessibilityOff = "Chat padhne ki ijaazat band hai",
            openWhatsAppFirst = "Pehle WhatsApp chat kholiye",
            reading = "Chat padh rahe hain…",
            noMessagesFound = "Koi message nahi mila",
            noOrderFound = "Koi order nahi mila",
            offlineFallback = "Server nahi mila — haath se bhariye",
            offlineParsed = "Offline padha — phone par hi. Jaanch kar lijiye.",
            importEmptyFile = "Chat khaali mila",
            importReading = "Chat padh rahe hain…",
            importNotAnExport = "Yeh WhatsApp export file nahi lagti. WhatsApp mein chat kholiye → Export chat → Without media, phir wahi file chuniye.",
            importCustomerUnknown = "Chat mein grahak ka naam nahi pehchana gaya. Yeh chat shayad aapki apni hai.",
            importServerRejected = { code -> "Server ne mana kar diya ($code)" },
            importServerUnreachable = "Server nahi mila — dekhiye ki backend chalu hai",
            importFailed = "Import nahi ho paya",
        ),
    )

    // ------------------------------------------------------------------
    // English
    // ------------------------------------------------------------------

    private val EN = Bundle(
        onboarding = Onboarding(
            welcomeTitle = "Welcome to VaaniLedger",
            welcomeSubtitle = "Your ledger, in your own words. Two quick questions before you start.",
            welcomeStart = "Get started",
            categoryTitle = "What does your shop sell?",
            categorySubtitle = "This helps the app recognise your products correctly when it reads an order.",
            languageTitle = "Which language should the app use?",
            languageSubtitle = "You can change this later from settings.",
            next = "Next",
            back = "Back",
            finish = "Done",
            stepOf = { step, total -> "Step $step of $total" },
        ),
        main = Main(
            appTitle = "VaaniLedger",
            tagline = "Your ledger, in your own words",
            overlayTitle = "Show over other apps",
            overlayDesc = "Lets the bubble float on top of WhatsApp. The app cannot work without this.",
            accessibilityTitle = "Permission to read chats",
            accessibilityDesc = "WhatsApp only. Only when you tap Capture. Never in the background.",
            statusOn = "ON",
            statusOffPrefix = "OFF — TAP TO TURN ON",
            bubbleStart = "Start the bubble",
            bubbleStop = "Stop the bubble",
            needPermissionFirst = "Grant permission first",
            helperText = "After starting the bubble, open WhatsApp — the bubble will appear on top.",
            waysHeading = "TWO WAYS TO READ ORDERS",
            openWhatsAppTitle = "Open WhatsApp",
            openWhatsAppDesc = "Live scan — read orders from the chat that's open",
            importTitle = "Import an exported chat",
            importDesc = "Full chat history — pick a file exported from WhatsApp",
            settingsTitle = "Settings",
            toastNoOverlayFirst = "Turn on 'Show over other apps' first",
            toastWhatsAppNotFound = "WhatsApp is not installed on this phone",
            toastOpenChatThenBubble = "Open a chat, then tap the bubble",
            toastNoFilePicker = "No file picker app was found",
        ),
        panel = Panel(
            statLabelSale = "SALE",
            statLabelCash = "CASH",
            statLabelCredit = "CREDIT",
            noEntriesToday = "No entries today",
            readChat = "Read this chat",
            readChatSub = "scan for orders",
            speak = "Speak an entry",
            speakSub = "voice",
            ledger = "Ledger",
            ledgerSub = "all customers",
        ),
        review = Review(
            allDone = "All done",
            newOrder = { n -> if (n == 1) "1 new order" else "$n new orders" },
            approveAll = "Approve all",
            approveAllSub = { n -> "save all $n" },
            emptyTitle = "No new orders found.",
            emptySubtitle = "Anything you already saved won't show again — but anything you said 'Skip' to will.",
            close = "Close",
            save = "Save",
            saveSub = "add to ledger",
            skip = "Skip",
            skipSub = "not now",
            newCustomer = "NEW CUSTOMER",
            fromConversation = "FROM CONVERSATION",
            noPriceYet = "NO PRICE YET",
            toastSaved = { amount -> "$amount added to the ledger" },
            toastSavedNoAmount = "Entry saved",
            toastSkipped = "Skipped — you can find it again later",
            toastSaveFailed = "Could not save",
            toastApprovedCount = { n -> "$n entries saved" },
            modeCredit = "CREDIT",
            modeCash = "CASH",
            modeOnline = "ONLINE",
        ),
        capture = Capture(
            accessibilityOff = "Chat-reading permission is off",
            openWhatsAppFirst = "Open a WhatsApp chat first",
            reading = "Reading the chat…",
            noMessagesFound = "No messages found",
            noOrderFound = "No order found",
            offlineFallback = "Could not reach the server — enter it by hand",
            offlineParsed = "Read offline, on the phone itself. Please check it.",
            importEmptyFile = "The chat file was empty",
            importReading = "Reading the chat…",
            importNotAnExport = "This doesn't look like a WhatsApp export. In WhatsApp: open the chat → Export chat → Without media, then pick that file.",
            importCustomerUnknown = "Could not tell who the customer is in this chat. It might be your own conversation.",
            importServerRejected = { code -> "The server rejected this ($code)" },
            importServerUnreachable = "Could not reach the server — check that the backend is running",
            importFailed = "Import failed",
        ),
    )
}
