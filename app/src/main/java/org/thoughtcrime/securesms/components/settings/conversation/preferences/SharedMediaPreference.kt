package org.thoughtcrime.securesms.components.settings.conversation.preferences

import android.database.Cursor
import android.view.View
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.components.ThreadPhotoRailView
import org.thoughtcrime.securesms.components.settings.PreferenceModel
import org.thoughtcrime.securesms.database.MediaDatabase
import org.thoughtcrime.securesms.mms.GlideApp
import org.thoughtcrime.securesms.util.MappingAdapter
import org.thoughtcrime.securesms.util.MappingViewHolder
import org.thoughtcrime.securesms.util.ViewUtil

/**
 * Renders the shared media photo rail.
 */
object SharedMediaPreference {

  fun register(adapter: MappingAdapter) {
    adapter.registerFactory(Model::class.java, MappingAdapter.LayoutFactory(::ViewHolder, R.layout.conversation_settings_shared_media))
  }

  class Model(
    val mediaCursor: Cursor,
    val onMediaRecordClick: (MediaDatabase.MediaRecord, Boolean) -> Unit
  ) : PreferenceModel<Model>() {
    override fun areItemsTheSame(newItem: Model): Boolean {
      return newItem.mediaCursor == mediaCursor
    }
  }

  private class ViewHolder(itemView: View) : MappingViewHolder<Model>(itemView) {

    private val rail: ThreadPhotoRailView = itemView.findViewById(R.id.rail_view)

    override fun bind(model: Model) {
      rail.setCursor(GlideApp.with(rail), model.mediaCursor)
      rail.setListener {
        model.onMediaRecordClick(it, ViewUtil.isLtr(rail))
      }
    }
  }
}