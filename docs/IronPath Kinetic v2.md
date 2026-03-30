# Design System Document: Kinetic Precision

## 1. Overview & Creative North Star: "The Neon Engine"
This design system is engineered for high-performance environments where speed and accuracy are paramount. We are moving away from the static "dashboard" look toward a **Kinetic Precision** aesthetic. 

**The Creative North Star: The Neon Engine.** 
Imagine a high-performance machine operating in a dark hangar; the only light comes from the glow of the interface itself. We achieve this through "Organic Brutalism"—using the raw, unapologetic geometric nature of Space Grotesk paired with sophisticated, layered dark surfaces. We break the "template" look by using intentional asymmetry in layouts, allowing elements to overlap slightly to create a sense of forward motion.

---

## 2. Colors & Surface Architecture
The palette is rooted in a deep charcoal (`#0e0e0e`) to maximize the "pop" of our high-voltage Electric Green (`#39FF14`).

### The "No-Line" Rule
**Standard 1px borders are strictly prohibited for sectioning.** To define boundaries, use tonal shifts between surface tiers. A section should be distinguished by moving from `surface` to `surface-container-low`, creating a "milled from a single block" feel rather than a "pasted on" look.

### Surface Hierarchy & Nesting
Treat the UI as a physical stack of high-tech materials.
*   **Base Layer:** `surface` (#0e0e0e) for the global background.
*   **Secondary Zones:** Use `surface-container-low` (#131313) for large layout blocks.
*   **Interactive Components:** Use `surface-container-high` (#20201f) or `highest` (#262626) for cards and modals.
*   **The Depth Hack:** Nested containers should always move *up* the hierarchy (getting lighter) as they get smaller or more focused.

### The "Glass & Gradient" Rule
To prevent the dark theme from feeling "flat," use Glassmorphism for floating panels. 
*   **Floating Elements:** Use a background color of `surface-variant` at 60% opacity with a `backdrop-filter: blur(20px)`.
*   **Signature Textures:** For Hero CTAs, use a linear gradient: `primary` (#8eff71) to `primary-container` (#2ff801) at a 135-degree angle. This adds "kinetic soul" to the buttons, making them feel energized.

---

## 3. Typography: The Space Grotesk Scale
Space Grotesk’s tabular figures and geometric terminals provide the "precision" in our vibe.

*   **Display (lg/md/sm):** Used for data-heavy hero numbers or high-impact headlines. Set with `letter-spacing: -0.04em` to feel tight and engineered.
*   **Headline & Title:** Use for section headers. In `headline-lg`, capitalize the first word only (Sentence case) to maintain an editorial, sophisticated tone.
*   **Body (lg/md/sm):** The workhorse. Always use `on-surface` (#ffffff) for maximum legibility. For secondary metadata, use `on-surface-variant` (#adaaaa).
*   **Labels:** These are our "Technical Specs." Use `label-md` or `label-sm` in all-caps with `letter-spacing: 0.1em` to mimic industrial serial numbers.

---

## 4. Elevation & Depth: Tonal Layering
We do not use shadows to lift objects; we use light and transparency.

*   **The Layering Principle:** Depth is achieved by stacking. A `surface-container-lowest` (#000000) card placed on a `surface-container` (#1a1a1a) section creates a "recessed" look, suggesting a precision-cut slot in the interface.
*   **Ambient Glow:** When a floating state is required (e.g., a dropdown), use an "Ambient Glow" instead of a shadow. Apply a shadow with a 40px blur, 0px offset, and a color of `primary` at 8% opacity. This makes the component look like it’s emitting light onto the surface below.
*   **The Ghost Border:** If a separator is required for accessibility, use a "Ghost Border": 1px solid `outline-variant` at 15% opacity. Never use a 100% opaque border.

---

## 5. Components: Built for Speed

### Buttons (The Kinetic Triggers)
*   **Primary:** Solid `primary` background with `on-primary` text. Use `rounded-sm` (0.125rem) corners for a sharp, aggressive look.
*   **Secondary:** `outline` (#767575) Ghost Border with `on-surface` text. On hover, the background shifts to `surface-bright`.
*   **Tertiary:** Text-only in `primary` color, all-caps `label-md` style.

### Input Fields
*   **Styling:** Background set to `surface-container-highest`, no border. A 2px bottom-bar in `outline-variant` that transforms to `primary` on focus.
*   **Interaction:** Placeholder text should be `on-surface-variant` at 50% opacity.

### Cards & Lists (The Modern Feed)
*   **No Dividers:** Forbid the use of horizontal rules. Separate list items using the spacing scale (e.g., `spacing-4` or 0.9rem) and subtle background alternating between `surface` and `surface-container-low`.
*   **Data Visualization:** Any graph or sparkline must use the `primary` electric green. Use a `0.5px` stroke for the line to maintain the "precise" aesthetic.

### Additional Component: The "Status Pulse"
For real-time data, use a small 8px circle of `primary` with a CSS animation creating a 20px expanding ring (0% opacity to 30% back to 0%). This reinforces the "Kinetic" nature of the system.

---

## 6. Do’s and Don’ts

### Do:
*   **Use Asymmetry:** Align text to the left but place supporting data or imagery on a slightly offset grid to create visual tension.
*   **Embrace the Dark:** Ensure `background` (#0e0e0e) remains the dominant color. The Electric Green is a scalpel, not a sledgehammer—use it sparingly for high-value actions.
*   **Use Tight Spacing:** Use `0.2rem` to `0.5rem` for internal component padding to feel "high-density" and professional.

### Don't:
*   **No Rounded Corners:** Avoid `rounded-full` or `rounded-xl` for primary containers. It softens the "Kinetic" vibe too much. Stick to `DEFAULT` (0.25rem) or `sm`.
*   **No Pure Grey Shadows:** Traditional drop shadows look "muddy" on charcoal. Use the Ambient Glow (tinted with primary green) instead.
*   **No Centered Layouts:** Avoid centering large blocks of text. It feels like a generic landing page. Keep everything "Engineered"—flush left or flush right.