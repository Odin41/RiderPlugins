package com.github.nsconverter

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class NamespaceConverterTest {

    @Test
    fun `detects block-scoped namespace`() {
        val code = """
            namespace MyApp.Services
            {
                class Foo {}
            }
        """.trimIndent()
        assertTrue(NamespaceConverter.hasBlockScopedNamespace(code))
    }

    @Test
    fun `does not detect file-scoped namespace`() {
        val code = "namespace MyApp.Services;\n\nclass Foo {}"
        assertFalse(NamespaceConverter.hasBlockScopedNamespace(code))
    }

    @Test
    fun `converts simple namespace`() {
        val input = """
namespace MyApp
{
    class Foo
    {
        void Bar() {}
    }
}
""".trimIndent()

        val expected = """
namespace MyApp;
class Foo
{
    void Bar() {}
}
""".trimIndent()

        val result = NamespaceConverter.convert(input).trim()
        assertEquals(expected, result)
    }

    @Test
    fun `preserves using directives before namespace`() {
        val input = """
using System;
using System.Collections.Generic;

namespace MyApp.Models
{
    public class Person
    {
        public string Name { get; set; }
    }
}
""".trimIndent()

        val result = NamespaceConverter.convert(input)
        assertTrue(result.contains("using System;"))
        assertTrue(result.contains("namespace MyApp.Models;"))
        assertTrue(result.contains("public class Person"))
        assertFalse(result.contains("namespace MyApp.Models\n") || result.contains("namespace MyApp.Models\r\n"))
    }

    @Test
    fun `handles braces inside strings`() {
        val input = """
namespace Test
{
    class Foo
    {
        string s = "hello { world }";
    }
}
""".trimIndent()

        val result = NamespaceConverter.convert(input)
        assertTrue(result.contains("namespace Test;"))
        assertTrue(result.contains("""string s = "hello { world }";"""))
    }

    @Test
    fun `throws on missing closing brace`() {
        val input = """
namespace Broken
{
    class Foo {}
"""
        assertThrows(NamespaceConverter.ConversionException::class.java) {
            NamespaceConverter.convert(input)
        }
    }
}
