/*
 * Copyright (c) 2018 DuckDuckGo
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.duckduckgo.app.tabs.ui

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.IntDef
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView.Adapter
import androidx.recyclerview.widget.RecyclerView.ViewHolder
import com.bumptech.glide.Glide
import com.bumptech.glide.RequestManager
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.duckduckgo.app.browser.databinding.ItemTabGridBinding
import com.duckduckgo.app.browser.databinding.ItemTabListBinding
import com.duckduckgo.app.browser.favicon.FaviconManager
import com.duckduckgo.app.browser.tabpreview.TabEntityDiffCallback
import com.duckduckgo.app.browser.tabpreview.TabEntityDiffCallback.Companion.DIFF_KEY_PREVIEW
import com.duckduckgo.app.browser.tabpreview.TabEntityDiffCallback.Companion.DIFF_KEY_TITLE
import com.duckduckgo.app.browser.tabpreview.TabEntityDiffCallback.Companion.DIFF_KEY_URL
import com.duckduckgo.app.browser.tabpreview.TabEntityDiffCallback.Companion.DIFF_KEY_VIEWED
import com.duckduckgo.app.browser.tabpreview.WebViewPreviewPersister
import com.duckduckgo.app.tabs.model.TabEntity
import com.duckduckgo.app.tabs.model.TabSwitcherData.LayoutType
import com.duckduckgo.app.tabs.ui.TabSwitcherAdapter.TabSwitcherViewHolder.TabBasedViewHolder
import com.duckduckgo.app.tabs.ui.TabSwitcherAdapter.TabSwitcherViewHolder.ViewType
import com.duckduckgo.common.ui.view.show
import com.duckduckgo.common.utils.DispatcherProvider
import com.duckduckgo.common.utils.swap
import java.io.File
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import kotlin.Int

class TabSwitcherAdapter(
    private val itemClickListener: TabSwitcherListener,
    private val webViewPreviewPersister: WebViewPreviewPersister,
    private val lifecycleOwner: LifecycleOwner,
    private val faviconManager: FaviconManager,
    private val dispatchers: DispatcherProvider,
) : Adapter<ViewHolder>() {

    private val list = mutableListOf<TabSwitcherItem>()
    private var isDragging: Boolean = false
    private var layoutType: LayoutType = LayoutType.GRID

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long {
        return list[position].id.hashCode().toLong()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when(viewType) {
           ViewType.GRID_TAB -> {
                val binding = ItemTabGridBinding.inflate(inflater, parent, false)
                return TabSwitcherViewHolder.GridViewHolder.TabViewHolder(binding)
            }
            ViewType.LIST_TAB -> {
                val binding = ItemTabListBinding.inflate(inflater, parent, false)
                return TabSwitcherViewHolder.ListViewHolder.TabViewHolder(binding)
            }
            else -> throw IllegalArgumentException("Unknown viewType: $viewType")
        }
    }

    override fun getItemViewType(position: Int): Int =
        when(list[position]) {
            is TabSwitcherItem.Tab -> {
            when (layoutType) {
                LayoutType.GRID -> ViewType.GRID_TAB
                LayoutType.LIST -> ViewType.LIST_TAB
            }
        }
    }

    override fun getItemCount(): Int = list.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        when (holder) {
            is TabSwitcherViewHolder.GridViewHolder.TabViewHolder -> {
                val tab = (list[position] as TabSwitcherItem.Tab).tabEntity
                bindGridTab(holder, tab)
            }
            is TabSwitcherViewHolder.ListViewHolder.TabViewHolder -> {
                val tab = (list[position] as TabSwitcherItem.Tab).tabEntity
                bindListTab(holder, tab)
            }
        }
    }

    private fun bindListTab(holder: TabSwitcherViewHolder.ListViewHolder.TabViewHolder, tab: TabEntity) {
        val context = holder.binding.root.context
        holder.title.text = extractTabTitle(tab, context)
        holder.url.text = tab.url ?: ""
        holder.url.visibility = if (tab.url.isNullOrEmpty()) View.GONE else View.VISIBLE
        updateUnreadIndicator(holder, tab)
        loadFavicon(tab, holder.favicon)
        attachTabClickListeners(
            holder = holder,
            tab = tab,
            onTabClosedClickListener = {
                itemClickListener.onTabDeleted(holder.bindingAdapterPosition, false)
            }
        )
    }

    private fun bindGridTab(holder: TabSwitcherViewHolder.GridViewHolder.TabViewHolder, tab: TabEntity) {
        val context = holder.binding.root.context
        val glide = Glide.with(context)
        holder.title.text = extractTabTitle(tab, context)
        updateUnreadIndicator(holder, tab)
        loadFavicon(tab, holder.favicon)
        loadTabPreviewImage(tab, glide, holder)
        attachTabClickListeners(
            holder = holder,
            tab = tab,
            onTabClosedClickListener = {
                itemClickListener.onTabDeleted(holder.bindingAdapterPosition, false)
            }
        )
    }

    private fun extractTabTitle(tab: TabEntity, context: Context): String {
        var title = tab.displayTitle(context)
        title = title.removeSuffix(DUCKDUCKGO_TITLE_SUFFIX)
        return title
    }

    private fun updateUnreadIndicator(holder: TabBasedViewHolder, tab: TabEntity) {
        holder.tabUnread.visibility = if (tab.viewed) View.INVISIBLE else View.VISIBLE
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) {
            onBindViewHolder(holder, position)
            return
        }

        when(holder.itemViewType) {
           ViewType.GRID_TAB -> {
               val viewHolder = holder as TabSwitcherViewHolder.GridViewHolder.TabViewHolder
               val tab = list[position] as TabSwitcherItem.Tab
               for (payload in payloads) {
                   val bundle = payload as Bundle
                   for (key in bundle.keySet()) {
                       Timber.v("$key changed - Need an update for $tab")
                   }

                   if (bundle.containsKey(DIFF_KEY_PREVIEW)) {
                       loadTabPreviewImage(tab.tabEntity, Glide.with(viewHolder.rootView), viewHolder)
                   }

                   bundle.getString(DIFF_KEY_TITLE)?.let {
                       holder.title.text = it
                   }

                   if (bundle.containsKey(DIFF_KEY_VIEWED)) {
                       updateUnreadIndicator(holder, tab.tabEntity)
                   }
               }
           }
            ViewType.LIST_TAB -> {
                val viewHolder = holder as TabSwitcherViewHolder.ListViewHolder.TabViewHolder
                val tab = list[position] as TabSwitcherItem.Tab
                for (payload in payloads) {
                    val bundle = payload as Bundle
                    for (key in bundle.keySet()) {
                        Timber.v("$key changed - Need an update for $tab")
                    }

                    bundle.getString(DIFF_KEY_URL)?.let {
                        viewHolder.url.show()
                        viewHolder.url.text = it
                    }

                    bundle.getString(DIFF_KEY_TITLE)?.let {
                        viewHolder.title.text = it
                    }

                    if (bundle.containsKey(DIFF_KEY_VIEWED)) {
                        updateUnreadIndicator(viewHolder, tab.tabEntity)
                    }
                }
            }

        }
/*
        val tab = list[position]
        for (payload in payloads) {
            val bundle = payload as Bundle
            for (key in bundle.keySet()) {
                Timber.v("$key changed - Need an update for $tab")
            }

            when (holder) {
                is TabSwitcherViewHolder.GridViewHolder.TabViewHolder -> {
                    if (bundle.containsKey(DIFF_KEY_PREVIEW)) {
                        loadTabPreviewImage(tab, Glide.with(holder.rootView), holder)
                    }
                }
                is TabSwitcherViewHolder.ListViewHolder.TabViewHolder -> {
                    bundle.getString(DIFF_KEY_URL)?.let {
                        holder.url.show()
                        holder.url.text = it
                    }
                }
            }

            if (holder is TabBasedViewHolder) {
                bundle.getString(DIFF_KEY_TITLE)?.let {
                    holder.title.text = it
                }

                if (bundle.containsKey(DIFF_KEY_VIEWED)) {
                    updateUnreadIndicator(holder, tab)
                }
            }
        }*/
    }

    private fun loadFavicon(tab: TabEntity, view: ImageView) {
        val url = tab.url ?: return
        lifecycleOwner.lifecycleScope.launch {
            faviconManager.loadToViewFromLocalWithPlaceholder(tab.tabId, url, view)
        }
    }

    private fun loadTabPreviewImage(tab: TabEntity, glide: RequestManager, holder: TabSwitcherViewHolder.GridViewHolder.TabViewHolder) {
        val previewFile = tab.tabPreviewFile ?: return glide.clear(holder.tabPreview)

        lifecycleOwner.lifecycleScope.launch {
            val cachedWebViewPreview = withContext(dispatchers.io()) {
                File(webViewPreviewPersister.fullPathForFile(tab.tabId, previewFile)).takeIf { it.exists() }
            }

            if (cachedWebViewPreview == null) {
                glide.clear(holder.tabPreview)
                return@launch
            }

            glide.load(cachedWebViewPreview)
                .transition(DrawableTransitionOptions.withCrossFade())
                .into(holder.tabPreview)

            holder.tabPreview.show()
        }
    }

    private fun attachTabClickListeners(holder: TabBasedViewHolder, tab: TabEntity, onTabClosedClickListener: () -> Unit) {
        holder.rootView.setOnClickListener {
            if (!isDragging) {
                itemClickListener.onTabSelected(tab)
            }
        }
        holder.close.setOnClickListener {
            onTabClosedClickListener()
        }
    }

    fun updateData(updatedList: List<TabSwitcherItem>) {
        val diffResult = DiffUtil.calculateDiff(TabEntityDiffCallback(list, updatedList))
        list.clear()
        list.addAll(updatedList)
        diffResult.dispatchUpdatesTo(this)
    }

    fun getTabSwitcherItem(position: Int): TabSwitcherItem? = list.getOrNull(position)

    fun adapterPositionForTab(tabId: String?): Int = list.indexOfFirst {
        it is TabSwitcherItem.Tab && it.tabEntity.tabId == tabId
    }

    fun onDraggingStarted() {
        isDragging = true
    }

    fun onDraggingFinished() {
        isDragging = false
    }

    fun onTabMoved(from: Int, to: Int) {
        val swapped = list.swap(from, to)
        updateData(swapped)
    }

    @SuppressLint("NotifyDataSetChanged")
    fun onLayoutTypeChanged(layoutType: LayoutType) {
        this.layoutType = layoutType
        notifyDataSetChanged()
    }

    companion object {
        private const val DUCKDUCKGO_TITLE_SUFFIX = "at DuckDuckGo"
    }

    sealed class TabSwitcherViewHolder(@ViewType open val viewType: Int, open val rootView: View): ViewHolder(rootView) {

        @IntDef(ViewType.GRID_TAB, ViewType.LIST_TAB)
        annotation class ViewType {
            companion object {
                const val GRID_TAB = 0
                const val LIST_TAB = 1
            }
        }

        sealed class GridViewHolder(override val viewType: Int, override val rootView: View)
            : TabSwitcherViewHolder(viewType, rootView) {

            data class TabViewHolder(
                val binding: ItemTabGridBinding,
                override val rootView: View = binding.root,
                override val favicon: ImageView = binding.favicon,
                override val title: TextView = binding.title,
                override val close: ImageView = binding.close,
                override val tabUnread: ImageView = binding.tabUnread,
            ) : GridViewHolder(viewType = ViewType.GRID_TAB, binding.root), TabBasedViewHolder {
                val tabPreview: ImageView = binding.tabPreview
            }
        }

        sealed class ListViewHolder(override val viewType: Int, override val rootView: View)
            : TabSwitcherViewHolder(viewType, rootView) {

            data class TabViewHolder(
                val binding: ItemTabListBinding,
                override val rootView: View = binding.root,
                override val favicon: ImageView = binding.favicon,
                override val title: TextView = binding.title,
                override val close: ImageView = binding.close,
                override val tabUnread: ImageView = binding.tabUnread,
            ) : ListViewHolder(ViewType.LIST_TAB, binding.root), TabBasedViewHolder {
                val url: TextView = binding.url
            }
        }

        interface TabBasedViewHolder {
            val rootView: View
            val favicon: ImageView
            val title: TextView
            val close: ImageView
            val tabUnread: ImageView
        }
    }
}
