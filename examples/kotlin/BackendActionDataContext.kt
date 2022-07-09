package com.jetbrains.rdserver.actions

import com.CWMDataKeys
import com.intellij.ide.DataManager
import com.intellij.ide.ProhibitAWTEvents
import com.intellij.ide.impl.DataManagerImpl
import com.intellij.ide.impl.GetDataRuleType
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.InjectedDataKeys
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.actionSystem.impl.AsyncDataContext
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.diagnostic.runAndLogException
import com.intellij.openapi.diagnostic.trace
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.UserDataHolder
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.reference.SoftReference
import com.intellij.util.ObjectUtils
import com.intellij.util.application
import com.intellij.util.keyFMap.KeyFMap
import com.jetbrains.rd.ide.model.EditorComponentId
import com.jetbrains.rd.ide.model.ToolWindowComponentId
import com.jetbrains.rdserver.core.GuestProjectSession
import com.jetbrains.rdserver.editors.BackendEditorHost
import java.awt.Component
import java.lang.ref.Reference
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

class BackendActionDataContext(
  private val session: GuestProjectSession,
  private val actionId: String,
  private val requestTimestampSet: ActionTimestampSet,
  private val actualTimestampSet: ActionTimestampSet,
  private val parentContext: DataContext?,
  private val handler: TimestampUpgradeResultHandler
) : AsyncDataContext, UserDataHolder {
  companion object {
    private val LOG = logger<BackendActionDataContext>()

    private val ourExplicitNull = ObjectUtils.sentinel("explicit.null")
  }

  private val componentRef: Reference<Component>?
  private val dataManager = DataManager.getInstance() as DataManagerImpl
  private val userData = AtomicReference(KeyFMap.EMPTY_MAP)
  private val cachedData = ConcurrentHashMap<String, Any>()
  private var failed = false

  init {
    val component = getContextComponent() ?: IdeFocusManager.getGlobalInstance().focusOwner
    componentRef = if (component == null) null else WeakReference(component)
  }

  override fun <T : Any?> getUserData(key: Key<T>): T? {  // Used by `ConfigurationContext.getFromContext(dataContext:place:)`
    return userData.get().get(key)
  }

  override fun <T : Any?> putUserData(key: Key<T>, value: T?) {  // Used by `ConfigurationContext.getFromContext(dataContext:place:)`
    while (true) {
      val map = userData.get()
      val newMap = if (value == null) map.minus(key) else map.plus(key, value)
      if (newMap === map || userData.compareAndSet(map, newMap)) {
        break
      }
    }
  }

  override fun getData(dataId: String): Any? {
    var data = getDataInner1(dataId)
    if (data == null) {
      data = dataManager.getDataFromRules(dataId, GetDataRuleType.CONTEXT, this::getDataInner1)
      cachedData[dataId] = data ?: ourExplicitNull
    }
    return if (data == ourExplicitNull) null else data
  }

  private fun getDataInner1(dataId: String): Any? {
    var data = getDataInner2(dataId)
    if (data == null) {
      for (provider in BackendActionDataProvider.EP_NAME.extensions) {
        data = LOG.runAndLogException {
          dataManager.getDataFromProviderAndRules(dataId, GetDataRuleType.PROVIDER) { id1 ->
            provider.getData(id1) { id2 ->
              val o = getDataInner2(id2)
              if (o == ourExplicitNull) null else o
            }
          }
        }
        if (data != null) break
      }
      cachedData[dataId] = data ?: ourExplicitNull
    }
    return data
  }

  private fun getDataInner2(dataId: String) : Any? {
    val component = SoftReference.dereference(componentRef)
    if (PlatformCoreDataKeys.CONTEXT_COMPONENT.`is`(dataId)) {
      return component
    }
    var data: Any?
    if (cachedData.containsKey(dataId)) {
      return cachedData[dataId]
    }
    data = dataManager.getDataFromProviderAndRules(dataId, GetDataRuleType.PROVIDER, this::getDataFromSelfOrParent)
    if (data == null && application.isDispatchThread) {
      data = calcData(dataId, component)
    }
    if (CommonDataKeys.EDITOR.`is`(dataId) || CommonDataKeys.HOST_EDITOR.`is`(dataId) || InjectedDataKeys.EDITOR.`is`(dataId)) {
      data = DataManagerImpl.validateEditor(data as Editor?, component)
    }
    cachedData[dataId] = data ?: ourExplicitNull
    return data
  }

  private fun calcData(dataId: String, component: Component?): Any? {
    ProhibitAWTEvents.start("getData").use { _ ->
      var c = component
      while (c != null) {
        val dataProvider = DataManagerImpl.getDataProviderEx(c)
        if (dataProvider == null) {
          c = c.parent
          continue
        }
        val data = dataManager.getDataFromProviderAndRules(dataId, GetDataRuleType.PROVIDER, dataProvider)
        if (data != null) return data
        c = c.parent
      }
    }
    return null
  }

  private fun getContextComponent(): Component? {
    return when (val componentId = getDataFromSelfOrParent(CWMDataKeys.CONTROL_ID.name)) {
      is EditorComponentId -> getEditorComponent(componentId)
      is ToolWindowComponentId -> null  // TODO
      else -> null
    }
  }

  private fun getEditorComponent(componentId: EditorComponentId): Component? {
    val editor = BackendEditorHost.getInstance(session).tryGetOwnRemoteEditor(componentId.textControlId) ?: return null
    return editor.contentComponent
  }

  private fun getDataFromSelfOrParent(dataId: String): Any? {
    if (failed) {
      LOG.warn("Requesting $dataId for already failed DataContext")
      throw TimestampUpgradeFailedException("Requesting $dataId for already failed DataContext")
    }
    val requestTimestamp = requestTimestampSet.getTimestamp(dataId)
    if (requestTimestamp == null) {
      return parentContext?.getData(dataId)
    }
    if (requestTimestamp is UpgradableTimestamp<*>) {
      when (val result = requestTimestamp.upgrade(actualTimestampSet)) {
        is TimestampUpgradeResult.Failure -> {
          failed = true
          handler.handleFailure(actionId, result)
          LOG.trace { "Timestamp upgrade for $dataId failed" }
          throw TimestampUpgradeFailedException(result.message)
        }
        is TimestampUpgradeResult.RetryRequest -> {
          failed = true
          handler.handleRetryRequest(actionId, result)
          LOG.trace { "Timestamp upgrade for $dataId required a retry" }
          throw TimestampUpgradeFailedException(result.message)
        }
        is TimestampUpgradeResult.Success<*> -> {
          return result.timestamp.getData()
        }
      }
    }
    else {
      return requestTimestamp.getData()
    }
  }
}