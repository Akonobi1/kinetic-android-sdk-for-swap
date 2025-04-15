package org.kin.kinetic.jupiter

import android.util.Log
import org.kin.kinetic.Keypair
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

class BasicAccountStorage(filesDir: File) {
    private var _account: Keypair? = null
    private var _filesDir: String = filesDir.absolutePath + "/kinetic/"

    fun account(): Keypair {
        return if (_account != null) {
            Log.d(BasicAccountStorage::class.java.name, "Account in mem: " + _account!!.publicKey)
            return _account!!
        } else {
            val accounts = getAllAccounts()
            if (!accounts.isEmpty()) {
                val accountJson = readFile(_filesDir, accounts[0] + ".key")
                _account = Keypair.fromJson(accountJson)
                return _account!!
            } else {
                Log.d(BasicAccountStorage::class.java.name, "No account stored")
                val account = Keypair.random()
                save(account)
                return account
            }
        }
    }

    fun clear(): Result<Unit> {
        _account = null
        return Result.success(Unit)
    }

    fun save(account: Keypair): Result<Unit> {
        writeToFile(_filesDir, account.publicKey + ".key", account.toJson())
        _account = account
        return Result.success(Unit)
    }

    private fun getAllAccounts(): List<String> {
        val accountDir = File(_filesDir)
        if (!accountDir.exists() || !accountDir.isDirectory) {
            return emptyList()
        }
        val accountDirectories = accountDir.list().asList()
            .map { name -> _filesDir + name }

        val accounts = accountDirectories
            .map { directory -> directory.split("/").last().split(".")[0] }

        return accounts
    }

    private fun writeToFile(directory: String, fileName: String, body: String): Boolean {
        var outputStream: FileOutputStream? = null
        return try {
            val file = File(directory, fileName)
            if (!file.parentFile.exists()) {
                file.parentFile.mkdirs()
            }

            if (!file.exists()) {
                file.createNewFile()
            }

            outputStream = FileOutputStream(file)
            outputStream.write(body.toByteArray(Charsets.UTF_8))

            true
        } catch (e: IOException) {
            e.printStackTrace()
            false
        } finally {
            outputStream?.close()
        }
    }

    private fun readFile(directory: String, fileName: String): String {
        var inputStream: FileInputStream? = null

        val file = File(directory, fileName)
        if (!file.exists()) {
            return ""
        }

        return try {
            inputStream = FileInputStream(file)
            var bytes = inputStream.readBytes()
            bytes.toString(Charsets.UTF_8)
        } catch (e: IOException) {
            e.printStackTrace()
            return ""

        } finally {
            inputStream?.close()
        }
    }
}