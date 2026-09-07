package com.pocketds.hub.state

/**
 * One line of a form.
 *
 * Three kinds cover everything the request dialog needs: pick one of a list,
 * tick a box, press a button.
 */
sealed interface FormRow {
    val id: String
    val label: String

    /** Pick one. Left and right cycle; A cycles forward. */
    data class Choice(
        override val id: String,
        override val label: String,
        val options: List<String>,
        val selected: Int = 0,
        /** Shown under the value, e.g. free space on a root folder. */
        val details: List<String> = emptyList()
    ) : FormRow {
        val value: String get() = options.getOrElse(selected) { "" }
        val detail: String get() = details.getOrElse(selected) { "" }
    }

    /** Tick a box. A, left and right all toggle it. */
    data class Toggle(
        override val id: String,
        override val label: String,
        val checked: Boolean = false,
        val detail: String = ""
    ) : FormRow

    /** Press it. A submits. */
    data class Action(
        override val id: String,
        override val label: String,
        val danger: Boolean = false
    ) : FormRow
}

/**
 * A gamepad-drivable form.
 *
 * Pure, and therefore tested on the JVM without a device: the rules that make
 * this usable -- where the cursor starts, whether the value list wraps, which
 * presses submit and which merely change something -- are exactly the ones that
 * are annoying to discover by hand on a handheld.
 *
 * Focus is an index rather than real View focus, for the same reason
 * [com.pocketds.hub.ui.ChoiceOverlay] does it: an overlay sits on top of a
 * screen whose views are all still focusable, and directional search would
 * happily walk out of the dialog.
 */
class FormModel(rows: List<FormRow>) {

    private val rows = rows.toMutableList()

    /** Where the cursor is. Starts on the first row that is not an action. */
    var index: Int = rows.indexOfFirst { it !is FormRow.Action }.coerceAtLeast(0)
        private set

    fun rows(): List<FormRow> = rows.toList()

    fun rowAt(position: Int): FormRow? = rows.getOrNull(position)

    fun current(): FormRow? = rows.getOrNull(index)

    /**
     * Move the cursor.
     *
     * Clamped, not wrapped. Wrapping means holding down on the last field puts
     * the cursor on "Request", which is precisely the press you do not want to
     * arrive at by accident.
     */
    fun move(delta: Int) {
        if (rows.isEmpty()) return
        index = (index + delta).coerceIn(0, rows.size - 1)
    }

    /**
     * Left or right on the current row.
     *
     * @return true if something changed, so the caller knows whether to redraw
     *   and whether to give haptic feedback.
     */
    fun adjust(delta: Int): Boolean {
        val row = rows.getOrNull(index) ?: return false
        return when (row) {
            is FormRow.Choice -> {
                if (row.options.size < 2) return false
                // Values *do* wrap: a list of seven quality profiles is a ring,
                // and having to press left six times to get back to the first is
                // worse than any accident wrapping could cause here.
                val next = ((row.selected + delta) % row.options.size + row.options.size) %
                    row.options.size
                rows[index] = row.copy(selected = next)
                true
            }
            is FormRow.Toggle -> {
                rows[index] = row.copy(checked = !row.checked)
                true
            }
            is FormRow.Action -> false
        }
    }

    /**
     * A on the current row.
     *
     * @return the id of an action row, or null when the press was absorbed by a
     *   field. Only a non-null result should submit anything.
     */
    fun activate(): String? {
        val row = rows.getOrNull(index) ?: return null
        return when (row) {
            is FormRow.Action -> row.id
            is FormRow.Choice -> {
                adjust(1)
                null
            }
            is FormRow.Toggle -> {
                adjust(1)
                null
            }
        }
    }

    /** Jump the cursor, e.g. after a tap. */
    fun focus(position: Int) {
        if (position in rows.indices) index = position
    }

    fun selectedIndex(id: String): Int =
        (rows.firstOrNull { it.id == id } as? FormRow.Choice)?.selected ?: -1

    fun isChecked(id: String): Boolean =
        (rows.firstOrNull { it.id == id } as? FormRow.Toggle)?.checked ?: false

    /** The ids of every ticked toggle, in row order. */
    fun checkedIds(prefix: String = ""): List<String> = rows
        .filterIsInstance<FormRow.Toggle>()
        .filter { it.checked && it.id.startsWith(prefix) }
        .map { it.id }

    /** Tick or untick without moving the cursor. */
    fun setChecked(id: String, checked: Boolean) {
        val position = rows.indexOfFirst { it.id == id }
        val row = rows.getOrNull(position) as? FormRow.Toggle ?: return
        rows[position] = row.copy(checked = checked)
    }

    /** Show or hide a group of rows, keeping the cursor somewhere sensible. */
    fun replace(rows: List<FormRow>) {
        val currentId = current()?.id
        this.rows.clear()
        this.rows.addAll(rows)
        val restored = rows.indexOfFirst { it.id == currentId }
        index = if (restored >= 0) {
            restored
        } else {
            rows.indexOfFirst { it !is FormRow.Action }.coerceAtLeast(0)
        }
    }
}
