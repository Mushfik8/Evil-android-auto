package com.autofreedom.app.car.screens

import android.os.Environment
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import com.autofreedom.app.media.MediaService
import java.io.File

/**
 * File explorer screen — browse device storage from the car screen.
 *
 * Shows files and folders in a list. Users can:
 * - Navigate into folders
 * - Tap media files to play them
 * - View file sizes and types
 *
 * Starts at the root of external storage (/storage/emulated/0).
 */
class FileExplorerScreen(
    carContext: CarContext,
    private val directoryPath: String = Environment.getExternalStorageDirectory().absolutePath,
    private val title: String = "Files"
) : Screen(carContext) {

    private val mediaExtensions = setOf(
        "mp3", "m4a", "flac", "wav", "ogg", "aac", "wma",  // Audio
        "mp4", "mkv", "avi", "mov", "webm", "3gp", "flv",  // Video
    )

    private val imageExtensions = setOf("jpg", "jpeg", "png", "gif", "bmp", "webp")

    override fun onGetTemplate(): Template {
        val listBuilder = ItemList.Builder()
        val dir = File(directoryPath)

        if (!dir.exists() || !dir.isDirectory) {
            listBuilder.addItem(
                Row.Builder()
                    .setTitle("Cannot access this folder")
                    .addText("Permission denied or folder doesn't exist")
                    .build()
            )
        } else {
            val files = dir.listFiles()
                ?.filter { !it.isHidden }  // Skip hidden files
                ?.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })
                ?: emptyList()

            if (files.isEmpty()) {
                listBuilder.addItem(
                    Row.Builder()
                        .setTitle("Empty folder")
                        .build()
                )
            } else {
                // Limit to 20 items (Android Auto restriction)
                for (file in files.take(20)) {
                    if (file.isDirectory) {
                        val childCount = file.listFiles()?.size ?: 0
                        listBuilder.addItem(
                            Row.Builder()
                                .setTitle("📁 ${file.name}")
                                .addText("$childCount items")
                                .setBrowsable(true)
                                .setOnClickListener {
                                    screenManager.push(
                                        FileExplorerScreen(
                                            carContext,
                                            file.absolutePath,
                                            file.name
                                        )
                                    )
                                }
                                .build()
                        )
                    } else {
                        val ext = file.extension.lowercase()
                        val icon = when {
                            ext in mediaExtensions && ext in setOf(
                                "mp4", "mkv", "avi", "mov", "webm", "3gp", "flv"
                            ) -> "🎬"
                            ext in mediaExtensions -> "🎵"
                            ext in imageExtensions -> "🖼️"
                            ext == "pdf" -> "📄"
                            ext == "txt" || ext == "md" -> "📝"
                            ext == "apk" -> "📦"
                            ext == "zip" || ext == "rar" || ext == "7z" -> "🗜️"
                            else -> "📎"
                        }

                        val sizeStr = formatFileSize(file.length())
                        val isPlayable = ext in mediaExtensions

                        val rowBuilder = Row.Builder()
                            .setTitle("$icon ${file.name}")
                            .addText("$sizeStr • ${ext.uppercase()}")

                        if (isPlayable) {
                            val isVideo = ext in setOf(
                                "mp4", "mkv", "avi", "mov", "webm", "3gp", "flv"
                            )
                            rowBuilder.setOnClickListener {
                                if (isVideo) {
                                    // Play video directly on car screen
                                    val uri = android.net.Uri.fromFile(file)
                                    screenManager.push(
                                        VideoPlayerScreen(
                                            carContext, uri, file.nameWithoutExtension
                                        )
                                    )
                                } else {
                                    // Play audio through media service
                                    val uri = android.net.Uri.fromFile(file)
                                    MediaService.instance?.playbackEngine?.playUri(
                                        uri, file.nameWithoutExtension
                                    )
                                }
                            }
                        }

                        listBuilder.addItem(rowBuilder.build())
                    }
                }
            }
        }

        return ListTemplate.Builder()
            .setTitle(title)
            .setHeaderAction(Action.BACK)
            .setSingleList(listBuilder.build())
            .build()
    }

    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes >= 1_073_741_824 -> String.format("%.1f GB", bytes / 1_073_741_824.0)
            bytes >= 1_048_576 -> String.format("%.1f MB", bytes / 1_048_576.0)
            bytes >= 1024 -> String.format("%.1f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }
}
