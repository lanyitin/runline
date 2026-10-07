package dev.lawlan.runline.engine.support

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.writeText
import kotlin.test.fail

/**
 * Runs the real `keytool` of the JDK the tests run on (JDK 25; the tests do not run on any other),
 * so that every keystore a test opens was written by the tool operators use (WI-41, ADR-019).
 */
object Keytool {
  private val executable: String =
      Path.of(System.getProperty("java.home"), "bin", "keytool").toString()

  /** Runs `keytool [args]` with [stdin] as its standard input; fails the test when it fails. */
  fun run(vararg args: String, stdin: String = ""): String {
    val process =
        ProcessBuilder(executable, *args)
            .redirectErrorStream(true)
            .redirectInput(ProcessBuilder.Redirect.PIPE)
            .start()
    process.outputStream.use { it.write(stdin.toByteArray(Charsets.UTF_8)) }
    val output = process.inputStream.readAllBytes().decodeToString()
    if (!process.waitFor(60, TimeUnit.SECONDS)) {
      process.destroyForcibly()
      fail("keytool ${args.joinToString(" ")} did not end within 60 seconds")
    }
    if (process.exitValue() != 0) {
      fail("keytool ${args.joinToString(" ")} failed (${process.exitValue()}): $output")
    }
    return output
  }
}

/**
 * Makes keystore files with the real `keytool` in a directory of its own. The password of every
 * file is in a password file next to it, passed to the tool as `-storepass:file`, as the operating
 * manual says (WI-42), never on a command line.
 */
class Keystores(val dir: Path = Files.createTempDirectory("keystores")) {
  /** A password file holding [password]; the tool reads the first line of it. */
  fun passwordFile(password: String = DEFAULT_PASSWORD, name: String = "storepass"): Path =
      dir.resolve(name).also { it.writeText(password + "\n") }

  /** A new PKCS12 file at [name] with a secret entry for each of [secrets] (alias to value). */
  fun pkcs12(
      name: String,
      secrets: Map<String, String> = emptyMap(),
      password: String = DEFAULT_PASSWORD,
  ): Path {
    val file = dir.resolve(name)
    val passwordFile = passwordFile(password, "$name.pw")
    if (secrets.isEmpty()) {
      // A file with no entry is made by the tool through an entry that is deleted again.
      importSecret(file, "scratch", "scratch-value", passwordFile)
      deleteEntry(file, "scratch", passwordFile)
    }
    secrets.forEach { (alias, value) -> importSecret(file, alias, value, passwordFile) }
    return file
  }

  /** Adds, as `-importpass` does, a secret entry; fails when [alias] exists (the tool refuses). */
  fun importSecret(file: Path, alias: String, value: String, passwordFile: Path) {
    Keytool.run(
        "-importpass",
        "-alias",
        alias,
        "-keystore",
        file.toString(),
        "-storetype",
        "pkcs12",
        "-storepass:file",
        passwordFile.toString(),
        stdin = "$value\n$value\n",
    )
  }

  fun deleteEntry(file: Path, alias: String, passwordFile: Path) {
    Keytool.run(
        "-delete",
        "-alias",
        alias,
        "-keystore",
        file.toString(),
        "-storepass:file",
        passwordFile.toString(),
    )
  }

  /** Adds a private key entry with a self-signed certificate. */
  fun privateKey(file: Path, alias: String, passwordFile: Path, storeType: String = "pkcs12") {
    Keytool.run(
        "-genkeypair",
        "-alias",
        alias,
        "-keyalg",
        "EC",
        "-groupname",
        "secp256r1",
        "-dname",
        "CN=$alias",
        "-keystore",
        file.toString(),
        "-storetype",
        storeType,
        "-storepass:file",
        passwordFile.toString(),
        "-keypass:file",
        passwordFile.toString(),
    )
  }

  /** Adds a trusted certificate entry: the certificate of a private key made for the purpose. */
  fun trustedCertificate(file: Path, alias: String, passwordFile: Path) {
    // A new pair every time, so that the same alias can be given another certificate.
    val unique = java.util.UUID.randomUUID()
    val source = dir.resolve("$alias.$unique.p12")
    privateKey(source, alias, passwordFile)
    val certificate = dir.resolve("$alias.$unique.cer")
    Keytool.run(
        "-exportcert",
        "-alias",
        alias,
        "-keystore",
        source.toString(),
        "-storepass:file",
        passwordFile.toString(),
        "-file",
        certificate.toString(),
    )
    Keytool.run(
        "-importcert",
        "-noprompt",
        "-alias",
        alias,
        "-file",
        certificate.toString(),
        "-keystore",
        file.toString(),
        "-storetype",
        "pkcs12",
        "-storepass:file",
        passwordFile.toString(),
    )
  }

  /** A keystore of another format, which the tool also writes: `JKS` or `JCEKS`. */
  fun otherFormat(name: String, storeType: String, password: String = DEFAULT_PASSWORD): Path {
    val file = dir.resolve(name)
    privateKey(file, "legacy", passwordFile(password, "$name.pw"), storeType)
    return file
  }

  companion object {
    const val DEFAULT_PASSWORD = "keystore-pass-1"
  }
}
