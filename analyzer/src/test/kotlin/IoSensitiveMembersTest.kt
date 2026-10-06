package dev.lawlan.runline.analyzer

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/** WI-21 / ADR-013: IO sensitive members are unsafe at member level, whatever the allow list. */
class IoSensitiveMembersTest {
  @TempDir lateinit var tmp: Path

  private val javaLang = AllowListEntry("java.lang")
  private val javaUtil = AllowListEntry("java.util")
  private val javaIo = AllowListEntry("java.io")

  /** Wide allow list: every package the sample code touches is trusted, so only members decide. */
  private val wide =
      AllowList(
          "v1",
          listOf(
              javaLang,
              javaUtil,
              javaIo,
              AllowListEntry("java.nio"),
              AllowListEntry("java.net"),
              AllowListEntry("kotlin"),
              AllowListEntry("org.jetbrains.annotations"),
          ),
      )

  private fun analyze(
      body: String,
      allowList: AllowList = wide,
      extra: Map<String, String> = emptyMap(),
      members: String = "",
  ): List<UnsafeReason> =
      SafetyAnalyzer()
          .analyze(
              CompiledJars.build(
                  tmp,
                  "p.jar",
                  mapOf(
                      "demo.Hello" to
                          CompiledJars.pipeline("demo.Hello", members = members, body = body)
                  ) + extra,
              ),
              allowList,
          )
          .pipelines
          .single()
          .reasons

  private fun ioMembers(reasons: List<UnsafeReason>) =
      reasons.filterIsInstance<UnsafeReason.IoSensitiveMember>().map { it.member }

  @Test
  fun `starting a process through ProcessBuilder is unsafe even though java lang is allow listed`() {
    val reasons = analyze("try { new ProcessBuilder(\"ls\").start(); } catch (Exception e) {}")

    assertEquals(
        listOf(
            UnsafeReason.IoSensitiveMember(
                "java.lang.ProcessBuilder.start()Ljava/lang/Process;",
                listOf("demo.Hello"),
            )
        ),
        reasons.filterIsInstance<UnsafeReason.IoSensitiveMember>(),
    )
  }

  @Test
  fun `every Runtime exec overload and ProcessBuilder startPipeline start a process`() {
    val reasons =
        analyze(
            """
            try {
              Runtime r = Runtime.getRuntime();
              String[] a = {"ls"};
              r.exec("ls");
              r.exec("ls", a);
              r.exec("ls", a, new java.io.File("."));
              r.exec(a);
              r.exec(a, a);
              r.exec(a, a, new java.io.File("."));
              ProcessBuilder.startPipeline(java.util.List.of());
            } catch (Exception e) {}
            """
        )

    assertEquals(
        listOf(
            "java.lang.ProcessBuilder.startPipeline(Ljava/util/List;)Ljava/util/List;",
            "java.lang.Runtime.exec(Ljava/lang/String;)Ljava/lang/Process;",
            "java.lang.Runtime.exec(Ljava/lang/String;[Ljava/lang/String;)Ljava/lang/Process;",
            "java.lang.Runtime.exec(Ljava/lang/String;[Ljava/lang/String;Ljava/io/File;)Ljava/lang/Process;",
            "java.lang.Runtime.exec([Ljava/lang/String;)Ljava/lang/Process;",
            "java.lang.Runtime.exec([Ljava/lang/String;[Ljava/lang/String;)Ljava/lang/Process;",
            "java.lang.Runtime.exec([Ljava/lang/String;[Ljava/lang/String;Ljava/io/File;)Ljava/lang/Process;",
        ),
        ioMembers(reasons),
    )
  }

  @Test
  fun `loading native code through System, Runtime, Linker or SymbolLookup is unsafe`() {
    val reasons =
        analyze(
            """
            try {
              System.load("/x.so");
              System.loadLibrary("x");
              Runtime.getRuntime().load("/x.so");
              Runtime.getRuntime().loadLibrary("x");
              java.lang.foreign.Linker.nativeLinker();
              java.lang.foreign.Arena arena = java.lang.foreign.Arena.global();
              java.lang.foreign.SymbolLookup.libraryLookup("x", arena);
              java.lang.foreign.SymbolLookup.libraryLookup(java.nio.file.Path.of("x"), arena);
            } catch (Exception e) {}
            """,
            wide.copy(entries = wide.entries + AllowListEntry("java.lang.foreign")),
        )

    assertEquals(
        listOf(
            "java.lang.Runtime.load(Ljava/lang/String;)V",
            "java.lang.Runtime.loadLibrary(Ljava/lang/String;)V",
            "java.lang.System.load(Ljava/lang/String;)V",
            "java.lang.System.loadLibrary(Ljava/lang/String;)V",
            "java.lang.foreign.Linker.nativeLinker()Ljava/lang/foreign/Linker;",
            "java.lang.foreign.SymbolLookup.libraryLookup(Ljava/lang/String;Ljava/lang/foreign/Arena;)Ljava/lang/foreign/SymbolLookup;",
            "java.lang.foreign.SymbolLookup.libraryLookup(Ljava/nio/file/Path;Ljava/lang/foreign/Arena;)Ljava/lang/foreign/SymbolLookup;",
        ),
        ioMembers(reasons),
    )
  }

  private val fileName = "\"out.txt\""
  private val file = "new java.io.File(\"out.txt\")"
  private val path = "java.nio.file.Path.of(\"in.txt\")"
  private val utf8 = "java.nio.charset.StandardCharsets.UTF_8"

  @Test
  fun `a Formatter that opens a file by name or File is unsafe`() {
    val reasons =
        analyze(
            """
            try {
              new java.util.Formatter($fileName);
              new java.util.Formatter($fileName, "UTF-8");
              new java.util.Formatter($fileName, "UTF-8", java.util.Locale.ROOT);
              new java.util.Formatter($fileName, $utf8, java.util.Locale.ROOT);
              new java.util.Formatter($file);
              new java.util.Formatter($file, "UTF-8");
              new java.util.Formatter($file, "UTF-8", java.util.Locale.ROOT);
              new java.util.Formatter($file, $utf8, java.util.Locale.ROOT);
            } catch (Exception e) {}
            """
        )

    assertEquals(
        listOf(
            "java.util.Formatter.<init>(Ljava/io/File;)V",
            "java.util.Formatter.<init>(Ljava/io/File;Ljava/lang/String;)V",
            "java.util.Formatter.<init>(Ljava/io/File;Ljava/lang/String;Ljava/util/Locale;)V",
            "java.util.Formatter.<init>(Ljava/io/File;Ljava/nio/charset/Charset;Ljava/util/Locale;)V",
            "java.util.Formatter.<init>(Ljava/lang/String;)V",
            "java.util.Formatter.<init>(Ljava/lang/String;Ljava/lang/String;)V",
            "java.util.Formatter.<init>(Ljava/lang/String;Ljava/lang/String;Ljava/util/Locale;)V",
            "java.util.Formatter.<init>(Ljava/lang/String;Ljava/nio/charset/Charset;Ljava/util/Locale;)V",
        ),
        ioMembers(reasons),
    )
  }

  @Test
  fun `a Formatter that only writes to memory or to an existing stream is safe`() {
    val reasons =
        analyze(
            """
            StringBuilder sb = new StringBuilder();
            new java.util.Formatter();
            new java.util.Formatter(sb);
            new java.util.Formatter(java.util.Locale.ROOT);
            new java.util.Formatter(sb, java.util.Locale.ROOT);
            new java.util.Formatter(System.out);
            new java.util.Formatter((java.io.OutputStream) System.out);
            new java.util.Formatter((java.io.OutputStream) System.out, $utf8, java.util.Locale.ROOT);
            """
        )

    assertEquals(emptyList(), reasons)
  }

  @Test
  fun `a Scanner over a File or Path is unsafe but one over a String or stream is safe`() {
    val sensitive =
        analyze(
            """
            try {
              new java.util.Scanner($file);
              new java.util.Scanner($file, "UTF-8");
              new java.util.Scanner($file, $utf8);
              new java.util.Scanner($path);
              new java.util.Scanner($path, "UTF-8");
              new java.util.Scanner($path, $utf8);
            } catch (Exception e) {}
            """
        )
    assertEquals(
        listOf(
            "java.util.Scanner.<init>(Ljava/io/File;)V",
            "java.util.Scanner.<init>(Ljava/io/File;Ljava/lang/String;)V",
            "java.util.Scanner.<init>(Ljava/io/File;Ljava/nio/charset/Charset;)V",
            "java.util.Scanner.<init>(Ljava/nio/file/Path;)V",
            "java.util.Scanner.<init>(Ljava/nio/file/Path;Ljava/lang/String;)V",
            "java.util.Scanner.<init>(Ljava/nio/file/Path;Ljava/nio/charset/Charset;)V",
        ),
        ioMembers(sensitive),
    )

    val memory =
        analyze(
            """
            new java.util.Scanner("1 2 3");
            new java.util.Scanner(new java.io.StringReader("1 2"));
            new java.util.Scanner(System.in);
            new java.util.Scanner(System.in, "UTF-8");
            new java.util.Scanner(System.in, $utf8);
            """
        )
    assertEquals(emptyList(), memory)
  }

  @Test
  fun `a PrintStream that opens a file is unsafe but one wrapping an existing stream is safe`() {
    val sensitive =
        analyze(
            """
            try {
              new java.io.PrintStream($fileName);
              new java.io.PrintStream($fileName, "UTF-8");
              new java.io.PrintStream($fileName, $utf8);
              new java.io.PrintStream($file);
              new java.io.PrintStream($file, "UTF-8");
              new java.io.PrintStream($file, $utf8);
            } catch (Exception e) {}
            """
        )
    assertEquals(
        listOf(
            "java.io.PrintStream.<init>(Ljava/io/File;)V",
            "java.io.PrintStream.<init>(Ljava/io/File;Ljava/lang/String;)V",
            "java.io.PrintStream.<init>(Ljava/io/File;Ljava/nio/charset/Charset;)V",
            "java.io.PrintStream.<init>(Ljava/lang/String;)V",
            "java.io.PrintStream.<init>(Ljava/lang/String;Ljava/lang/String;)V",
            "java.io.PrintStream.<init>(Ljava/lang/String;Ljava/nio/charset/Charset;)V",
        ),
        ioMembers(sensitive),
    )

    val wrapping =
        analyze(
            """
            try {
              java.io.OutputStream o = new java.io.ByteArrayOutputStream();
              new java.io.PrintStream(o);
              new java.io.PrintStream(o, true);
              new java.io.PrintStream(o, true, "UTF-8");
              new java.io.PrintStream(o, true, $utf8);
            } catch (Exception e) {}
            """
        )
    assertEquals(emptyList(), wrapping)
  }

  @Test
  fun `archive files and log handlers that open files or sockets are unsafe in every constructor`() {
    val reasons =
        analyze(
            """
            try {
              new java.util.zip.ZipFile($fileName);
              new java.util.zip.ZipFile($file, 1);
              new java.util.zip.ZipFile($file);
              new java.util.zip.ZipFile($file, 1, $utf8);
              new java.util.zip.ZipFile($fileName, $utf8);
              new java.util.zip.ZipFile($file, $utf8);
              new java.util.jar.JarFile($fileName);
              new java.util.jar.JarFile($fileName, true);
              new java.util.jar.JarFile($file);
              new java.util.jar.JarFile($file, true);
              new java.util.jar.JarFile($file, true, 1);
              new java.util.jar.JarFile($file, true, 1, Runtime.version());
              new java.util.logging.FileHandler();
              new java.util.logging.FileHandler($fileName);
              new java.util.logging.FileHandler($fileName, true);
              new java.util.logging.FileHandler($fileName, 1, 1);
              new java.util.logging.FileHandler($fileName, 1, 1, true);
              new java.util.logging.FileHandler($fileName, 1L, 1, true);
              new java.util.logging.SocketHandler();
              new java.util.logging.SocketHandler("host", 1);
            } catch (Exception e) {}
            """
        )

    val byOwner =
        ioMembers(reasons).groupBy { it.substringBefore(".<init>") }.mapValues { it.value.size }
    assertEquals(
        mapOf(
            "java.util.zip.ZipFile" to 6,
            "java.util.jar.JarFile" to 6,
            "java.util.logging.FileHandler" to 6,
            "java.util.logging.SocketHandler" to 2,
        ),
        byOwner,
    )
  }

  private val exec = "java.lang.Runtime.exec(Ljava/lang/String;)Ljava/lang/Process;"
  private val formatterByName = "java.util.Formatter.<init>(Ljava/lang/String;)V"
  private val start = "java.lang.ProcessBuilder.start()Ljava/lang/Process;"

  @Test
  fun `method references to IO sensitive members are references`() {
    val reasons =
        analyze(
            "Exec e = Runtime.getRuntime()::exec; Make m = java.util.Formatter::new;",
            extra = emptyMap(),
            members =
                "interface Exec { Process run(String s) throws Exception; } " +
                    "interface Make { Object make(String s) throws Exception; }",
        )

    assertEquals(listOf(exec, formatterByName), ioMembers(reasons))
  }

  @Test
  fun `a reference to a memory-only overload through a method reference stays safe`() {
    val reasons =
        analyze(
            "Make m = java.util.Scanner::new;",
            members = "interface Make { Object make(String s) throws Exception; }",
        )

    assertEquals(emptyList(), reasons)
  }

  private fun library(body: String) =
      mapOf(
          "com.lib.Helper" to
              "package com.lib; public class Helper { public static void work() { $body } }"
      )

  @Test
  fun `an IO sensitive member inside a bundled third-party class is reported with the path to it`() {
    val reasons =
        analyze(
            "com.lib.Helper.work();",
            extra = library("try { new ProcessBuilder(\"ls\").start(); } catch (Exception e) {}"),
        )

    assertEquals(
        listOf(
            UnsafeReason.NotAllowListed("com.lib.Helper", listOf("demo.Hello", "com.lib.Helper")),
            UnsafeReason.IoSensitiveMember(start, listOf("demo.Hello", "com.lib.Helper")),
        ),
        reasons,
    )
  }

  @Test
  fun `an IO sensitive member inside an allow listed library is trusted and not inspected`() {
    val reasons =
        analyze(
            "com.lib.Helper.work();",
            wide.copy(entries = wide.entries + AllowListEntry("com.lib")),
            library("try { new ProcessBuilder(\"ls\").start(); } catch (Exception e) {}"),
        )

    assertEquals(emptyList(), reasons)
  }

  @Test
  fun `the reason is independent of JVM exit and of classes outside the allow list`() {
    val reasons =
        analyze(
            """
            try {
              System.exit(1);
              new java.util.Scanner(new java.io.File("x"));
              new ProcessBuilder("ls").start();
            } catch (Exception e) {}
            """,
            AllowList("v1", listOf(javaLang, javaUtil)),
        )

    assertEquals(
        setOf(
            UnsafeReason.JvmExit("java.lang.System.exit", listOf("demo.Hello")),
            UnsafeReason.NotAllowListed("java.io.File", listOf("demo.Hello", "java.io.File")),
            UnsafeReason.IoSensitiveMember(start, listOf("demo.Hello")),
            UnsafeReason.IoSensitiveMember(
                "java.util.Scanner.<init>(Ljava/io/File;)V",
                listOf("demo.Hello"),
            ),
        ),
        reasons.toSet(),
    )
  }

  @Test
  fun `the verdict is unsafe with java lang java util and kotlin on the allow list`() {
    val report =
        SafetyAnalyzer()
            .analyze(
                CompiledJars.build(
                    tmp,
                    "v.jar",
                    mapOf(
                        "demo.Hello" to
                            CompiledJars.pipeline(
                                "demo.Hello",
                                body =
                                    "try { Runtime.getRuntime().exec(\"ls\"); } catch (Exception e) {}",
                            )
                    ),
                ),
                AllowList("v1", listOf(javaLang, javaUtil, AllowListEntry("kotlin"))),
            )

    assertEquals(Verdict.UNSAFE, report.pipelines.single().verdict)
  }

  private fun analyzeKotlin(vararg classes: Class<*>): Map<String, List<UnsafeReason>> =
      SafetyAnalyzer()
          .analyze(KotlinFixtures.jarOf(tmp, "k.jar", *classes), wide)
          .pipelines
          .associate { it.className to it.reasons }

  @Test
  fun `Kotlin direct calls and constructions of IO sensitive members are unsafe`() {
    val reasons = analyzeKotlin(demo.KotlinIoDirectPipeline::class.java)

    assertEquals(
        listOf(
            "java.lang.ProcessBuilder.start()Ljava/lang/Process;",
            "java.lang.Runtime.exec(Ljava/lang/String;)Ljava/lang/Process;",
            "java.lang.System.loadLibrary(Ljava/lang/String;)V",
            "java.util.Formatter.<init>(Ljava/lang/String;)V",
        ),
        ioMembers(reasons.getValue("demo.KotlinIoDirectPipeline")),
    )
  }

  @Test
  fun `Kotlin callable references to IO sensitive members are found in their synthetic classes`() {
    val reasons =
        analyzeKotlin(demo.KotlinIoReferencesPipeline::class.java)
            .getValue("demo.KotlinIoReferencesPipeline")

    assertEquals(
        setOf(
            "java.lang.ProcessBuilder.start()Ljava/lang/Process;",
            "java.lang.Runtime.exec(Ljava/lang/String;)Ljava/lang/Process;",
            "java.lang.System.loadLibrary(Ljava/lang/String;)V",
            "java.util.Formatter.<init>(Ljava/lang/String;)V",
        ),
        ioMembers(reasons).toSet(),
    )
    reasons.filterIsInstance<UnsafeReason.IoSensitiveMember>().forEach {
      assertEquals("demo.KotlinIoReferencesPipeline", it.path.first())
    }
  }

  @Test
  fun `calls the Kotlin compiler generates for lambdas, delegates and inline functions are found`() {
    val reasons =
        analyzeKotlin(demo.KotlinIoGeneratedPipeline::class.java)
            .getValue("demo.KotlinIoGeneratedPipeline")

    assertEquals(setOf(start, exec), ioMembers(reasons).toSet())
  }

  @Test
  fun `Kotlin code that uses only memory forms of the same classes is safe`() {
    val reasons =
        analyzeKotlin(demo.KotlinIoMemoryPipeline::class.java)
            .getValue("demo.KotlinIoMemoryPipeline")

    assertEquals(emptyList(), reasons)
  }

  @Test
  fun `the limitations state in Traditional Chinese what the IO sensitive member check covers`() {
    val limitations = SafetyReport.LIMITATIONS

    assertTrue(limitations.contains("只涵蓋已列出的 IO 敏感成員"))
    assertTrue(limitations.contains("啟動外部行程"))
    assertTrue(limitations.contains("載入原生程式碼"))
    assertTrue(limitations.contains("開啟檔案或網路"))
    assertTrue(limitations.contains("子類別"))
    assertTrue(limitations.contains("反射與動態載入"))
    assertTrue(limitations.contains("方法句柄與方法參照"))
  }
}
