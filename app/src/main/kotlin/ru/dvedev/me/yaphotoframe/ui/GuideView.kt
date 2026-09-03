package ru.dvedev.me.yaphotoframe.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import ru.dvedev.me.yaphotoframe.tuner.TunerAddress
import android.widget.TextView

/**
 * Что делать дальше — крупно, на весь экран.
 *
 * Показывается, пока рамке нечего показывать: сразу после установки и если
 * ссылка на папку перестала работать. Чёрный экран в этот момент выглядел бы
 * поломкой, а рамка — вещь для тех, кто не полезет в логи.
 *
 * Слева шаги, справа квадратик со ссылкой: экран широкий, и в один столбик всё
 * это не помещалось, обрезая последний шаг. Текст рассчитан на чтение с дивана —
 * телевизор смотрят с трёх-четырёх метров, и обычный размер интерфейса там
 * неразличим.
 */
class GuideView(
    context: Context,
    addresses: List<TunerAddress>,
    showingDemo: Boolean,
    donateUrl: String? = null,
    /**
     * Добавить шаг про назначение заставки. Нужен на экране приложения: там
     * непонятно, что делать дальше, а внутри заставки он был бы лишним.
     */
    assignStep: Boolean = false,
    /** Предел высоты, когда родитель его не задаёт (например, внутри прокрутки). */
    private val maxHeightPx: Int = 0,
) : LinearLayout(context) {

    /** Во сколько раз текст ужат, чтобы влезть; единица — как задумано. */
    private var textScale = 1f
    private val baseTextSizes = HashMap<TextView, Float>()

    /**
     * Ужимает текст, пока он не влезет в отведённую высоту.
     *
     * Телевизоры срезают до пяти процентов картинки по краям, разрешения и
     * шрифты разные, а подсказка с обрезанной последней строкой выглядит
     * поломкой. Проще подогнать текст, чем угадывать запас.
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val mode = MeasureSpec.getMode(heightMeasureSpec)
        var limit = if (mode == MeasureSpec.UNSPECIFIED) maxHeightPx else MeasureSpec.getSize(heightMeasureSpec)
        if (limit > 0) limit -= SAFE_MARGIN * 2
        var spec = heightMeasureSpec
        if (mode == MeasureSpec.UNSPECIFIED && maxHeightPx > 0) {
            spec = MeasureSpec.makeMeasureSpec(maxHeightPx, MeasureSpec.AT_MOST)
        }
        super.onMeasure(widthMeasureSpec, spec)
        if (limit <= 0) return

        var attempts = 0
        while (steps.measuredHeight + paddingTop + paddingBottom > limit &&
            textScale > MIN_TEXT_SCALE && attempts++ < 6
        ) {
            val needed = (steps.measuredHeight + paddingTop + paddingBottom).toFloat()
            textScale = (textScale * limit / needed * 0.98f).coerceAtLeast(MIN_TEXT_SCALE)
            applyTextScale()
            super.onMeasure(widthMeasureSpec, spec)
        }
    }

    private fun applyTextScale() {
        for ((view, base) in baseTextSizes) {
            view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, base * textScale)
        }
    }

    private fun TextView.sized(sp: Float): TextView {
        baseTextSizes[this] = sp
        textSize = sp * textScale
        return this
    }

    private val steps = LinearLayout(context).apply {
        orientation = VERTICAL
        gravity = Gravity.CENTER_VERTICAL
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(BACKGROUND)
        setPadding(SIDE_PADDING, PADDING, SIDE_PADDING, PADDING)

        // Колонка шагов забирает всё, что не занял квадратик. Всё её
        // содержимое кладётся именно в неё: попав в горизонтальный контейнер
        // напрямую, длинные адреса съедали ширину, и шаги схлопывались в ноль.
        addView(steps, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        steps.addView(title("Как настроить фоторамку"))
        steps.addView(
            subtitle(
                if (showingDemo) {
                    "Показывается папка для примера. " +
                        "Чтобы появились ваши снимки, укажите свою — это делается с телефона."
                } else {
                    "Укажите папку с фотографиями — это делается с телефона за минуту."
                }
            )
        )

        steps.addView(step(1, "Подключите телефон к той же сети, что и телевизор."))
        if (addresses.isEmpty()) {
            steps.addView(
                step(2, "Включите страницу настройки в настройках приложения на телевизоре.")
            )
        } else {
            steps.addView(step(2, "Наведите камеру телефона на код справа или откройте адрес:"))
            steps.addView(accent(addresses.first().url))
            // Второй адрес бывает, когда телевизор подключён и проводом, и по
            // воздуху. Показываем его мелко и с именем интерфейса — на случай,
            // если основной почему-то не открывается.
            addresses.drop(1).take(2).forEach {
                steps.addView(note("Ещё адрес (${it.interfaceName}): ${it.url}"))
            }
        }
        steps.addView(
            step(3, "На Яндекс.Диске сделайте папку с фото общедоступной и скопируйте ссылку.")
        )
        steps.addView(step(4, "Вставьте её на вкладке «Настройка» и нажмите «Задать папку»."))
        if (assignStep) {
            steps.addView(
                step(5, "Назначьте заставку: Настройки телевизора → Заставка → «Фоторамка»."),
            )
        }
        steps.addView(note("Там же выбираются вложенные папки и подбирается вид рамки."))
        steps.addView(
            note(
                "Сохраните страницу на телефоне — иначе адрес придётся искать заново. " +
                    "А чтобы увидеть эту подсказку снова, нажмите на пульте ↑ или ↓.",
            )
        )

        addQrCode(addresses.firstOrNull()?.url, donateUrl)
    }

    /** Квадратики со ссылками: телефон наводят прямо на экран. */
    private fun addQrCode(address: String?, donateUrl: String?) {
        val column = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        address?.let { url ->
            QrCode.render(url, QR_SIZE)?.let { column.addView(qrImage(it, QR_SIZE)) }
        }
        donateUrl?.let { url ->
            QrCode.render(url, DONATE_QR_SIZE)?.let {
                column.addView(
                    TextView(context).apply {
                        text = "Donate:"
                        textSize = 13f
                        setTextColor(MUTED)
                        setPadding(0, GAP * 3, 0, GAP / 2)
                    }
                )
                column.addView(qrImage(it, DONATE_QR_SIZE))
            }
        }

        if (column.childCount == 0) return
        addView(
            column,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                leftMargin = SIDE_PADDING
            },
        )
    }

    private fun qrImage(bitmap: android.graphics.Bitmap, size: Int) = ImageView(context).apply {
        setImageBitmap(bitmap)
        setPadding(QR_PADDING, QR_PADDING, QR_PADDING, QR_PADDING)
        setBackgroundColor(Color.WHITE)
        layoutParams = LinearLayout.LayoutParams(size + QR_PADDING * 2, size + QR_PADDING * 2)
    }

    private fun title(text: String) = TextView(context).apply {
        this.text = text
        sized(36f)
        setTextColor(TEXT)
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, 0, 0, GAP)
    }

    private fun subtitle(text: String) = TextView(context).apply {
        this.text = text
        sized(19f)
        setTextColor(MUTED)
        setPadding(0, 0, 0, GAP * 2)
    }

    private fun step(number: Int, text: String) = TextView(context).apply {
        this.text = "$number.   $text"
        sized(21f)
        setTextColor(TEXT)
        setPadding(0, GAP / 2, 0, GAP / 2)
    }

    private fun accent(text: String) = TextView(context).apply {
        this.text = text
        sized(30f)
        setTextColor(ACCENT)
        setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
        setPadding(STEP_INDENT, GAP / 2, 0, GAP)
    }

    private fun note(text: String) = TextView(context).apply {
        this.text = text
        sized(16f)
        setTextColor(MUTED)
        setPadding(0, GAP * 2, 0, 0)
    }

    private companion object {
        const val BACKGROUND = 0xFF14161C.toInt()
        const val TEXT = 0xFFE8E6E1.toInt()
        const val MUTED = 0xFF9AA1AE.toInt()
        const val ACCENT = 0xFFD9A441.toInt()
        const val PADDING = 48
        const val SIDE_PADDING = 96
        const val STEP_INDENT = 56
        const val GAP = 12
        const val QR_SIZE = 340
        const val DONATE_QR_SIZE = 115
        const val QR_PADDING = 24

        /** Пять процентов высоты Full HD: столько телевизоры срезают по краю. */
        const val SAFE_MARGIN = 54
        const val MIN_TEXT_SCALE = 0.55f
    }
}
