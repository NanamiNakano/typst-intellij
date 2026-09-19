package dev.thynanami.idea.typst.config

import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.PlainSyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.options.colors.AttributesDescriptor
import com.intellij.openapi.options.colors.ColorDescriptor
import com.intellij.openapi.options.colors.ColorSettingsPage
import dev.thynanami.idea.typst.TypstColor
import dev.thynanami.idea.typst.TypstIcons

private const val DOLLAR = "$"

class TypstColorSettingsPage : ColorSettingsPage {
    override fun getDisplayName() = "Typst"

    override fun getIcon() = TypstIcons.TYPST

    override fun getAttributeDescriptors(): Array<AttributesDescriptor> =
        TypstColor.entries.map { it.descriptor }.toTypedArray()

    override fun getColorDescriptors(): Array<ColorDescriptor> = ColorDescriptor.EMPTY_ARRAY

    override fun getHighlighter(): SyntaxHighlighter = PlainSyntaxHighlighter()

    override fun getAdditionalHighlightingTagToDescriptorMap(): Map<String, TextAttributesKey> =
        TypstColor.entries.associate { it.demoTag to it.attributes }

    override fun getDemoText() = """
        <comment>// Welcome to Typst!</comment>
        <keyword>#set</keyword> <function>text</function><punctuation>(</punctuation><variable>size</variable><punctuation>:</punctuation> <number>11pt</number><punctuation>)</punctuation>
        <keyword>#set</keyword> <function>heading</function><punctuation>(</punctuation><variable>numbering</variable><punctuation>:</punctuation> <string>"1.1"</string><punctuation>)</punctuation>

        <keyword>#let</keyword> <variable>accent</variable> <operator>=</operator> <function>rgb</function><punctuation>(</punctuation><string>"#4f46e5"</string><punctuation>)</punctuation>
        <keyword>#let</keyword> <function>greet</function><punctuation>(</punctuation><variable>name</variable><punctuation>)</punctuation> <operator>=</operator> <punctuation>[</punctuation>Hello, <punctuation>*</punctuation><variable>#name</variable><punctuation>*</punctuation>!<punctuation>]</punctuation>

        <heading>= Typst Example</heading> <label><intro></label>

        Typst combines <punctuation>*</punctuation><strong>strong text</strong><punctuation>*</punctuation>,
        <punctuation>_</punctuation><emphasis>emphasis</emphasis><punctuation>_</punctuation>, <raw>`raw text`</raw>, and
        <punctuation>*_</punctuation><strong_emphasis>scripting</strong_emphasis><punctuation>_*</punctuation> in a single document.

        Visit <link>https://typst.app</link> or jump back to <reference>@intro</reference>.

        <list_marker>-</list_marker> A bullet list
        <list_marker>-</list_marker> Styled <function>#text</function><punctuation>(</punctuation><variable>fill</variable><punctuation>:</punctuation> <variable>accent</variable><punctuation>)[</punctuation>content<punctuation>]</punctuation>
        <list_marker>+</list_marker> First numbered item
        <list_marker>+</list_marker> Second numbered item
        <list_marker>/</list_marker> <list_term>Term</list_term><punctuation>:</punctuation> A short definition

        <heading>== Scripting</heading>

        <keyword>#let</keyword> <variable>values</variable> <operator>=</operator> <punctuation>(</punctuation><number>1</number><punctuation>,</punctuation> <number>2</number><punctuation>,</punctuation> <number>3</number><punctuation>,</punctuation> <number>5</number><punctuation>,</punctuation> <number>8</number><punctuation>)</punctuation>
        <keyword>#let</keyword> <variable>config</variable> <operator>=</operator> <punctuation>(</punctuation>
          <variable>enabled</variable><punctuation>:</punctuation> <boolean>true</boolean><punctuation>,</punctuation>
          <variable>title</variable><punctuation>:</punctuation> <string>"Example"</string><punctuation>,</punctuation>
          <variable>count</variable><punctuation>:</punctuation> <number>42</number><punctuation>,</punctuation>
        <punctuation>)</punctuation>

        <function>#greet</function><punctuation>(</punctuation><string>"world"</string><punctuation>)</punctuation>

        <keyword>#if</keyword> <variable>config</variable><operator>.</operator><variable>enabled</variable> <punctuation>[</punctuation>
          The answer is <punctuation>#(</punctuation><variable>config</variable><operator>.</operator><variable>count</variable> <operator>+</operator> <number>1</number><punctuation>)</punctuation>.
        <punctuation>]</punctuation> <keyword>else</keyword> <punctuation>[</punctuation>
          Nothing to show.
        <punctuation>]</punctuation>

        <keyword>#for</keyword> <variable>value</variable> <keyword>in</keyword> <variable>values</variable> <punctuation>[</punctuation>
          Value: <variable>#value</variable> <escape>\</escape>
        <punctuation>]</punctuation>

        <heading>== Mathematics</heading>

        Inline math: <math_delimiter>$DOLLAR</math_delimiter><math_variable>a</math_variable><operator>^</operator><number>2</number> <operator>+</operator> <math_variable>b</math_variable><operator>^</operator><number>2</number> <operator>=</operator> <math_variable>c</math_variable><operator>^</operator><number>2</number><math_delimiter>$DOLLAR</math_delimiter>.

        <math_delimiter>$DOLLAR</math_delimiter> <math_variable>sum</math_variable><operator>_</operator><punctuation>(</punctuation><math_variable>i</math_variable><operator>=</operator><number>1</number><punctuation>)</punctuation><operator>^</operator><math_variable>n</math_variable> <math_variable>i</math_variable> <operator>=</operator> <punctuation>(</punctuation><math_variable>n</math_variable><punctuation>(</punctuation><math_variable>n</math_variable> <operator>+</operator> <number>1</number><punctuation>))</punctuation> <operator>/</operator> <number>2</number> <math_delimiter>$DOLLAR</math_delimiter>

        <heading>== Raw code</heading>

        <raw>```rust
        fn main() {
            println!("Hello, Typst!");
        }
        ```</raw>

        <comment>/* Block comments are highlighted too. */</comment>

        <function>#figure</function><punctuation>(</punctuation>
          <function>rect</function><punctuation>(</punctuation>
            <variable>width</variable><punctuation>:</punctuation> <number>100%</number><punctuation>,</punctuation>
            <variable>height</variable><punctuation>:</punctuation> <number>24pt</number><punctuation>,</punctuation>
            <variable>fill</variable><punctuation>:</punctuation> <function>luma</function><punctuation>(</punctuation><number>90%</number><punctuation>),</punctuation>
          <punctuation>),</punctuation>
          <variable>caption</variable><punctuation>:</punctuation> <punctuation>[</punctuation>A simple figure.<punctuation>],</punctuation>
        <punctuation>)</punctuation> <label><demo-figure></label>

        See <reference>@demo-figure</reference>, sized to
        <module>#calc</module><operator>.</operator><function>max</function><punctuation>(</punctuation><builtin>int</builtin><punctuation>(</punctuation><string>"42"</string><punctuation>),</punctuation> <number>0</number><punctuation>)</punctuation>.
        An unterminated <syntax_error>#let</syntax_error> is reported as an error.
    """.trimIndent()
}
