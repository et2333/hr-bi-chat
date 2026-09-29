import { ref } from 'vue'
import { defineStore } from 'pinia'

/**
 * 会话上下文 store（骨架）
 * S6 阶段填充：当前报表、语言、主题、会话会话 ID 等
 */
export const useSessionStore = defineStore('session', () => {
  // ---- state 骨架 ----
  /** 当前查看的报表 ID */
  const activeReportId = ref<string | null>(null)
  /** 界面语言 */
  const language = ref<'zh' | 'en'>('zh')

  // ---- actions 骨架（空实现，S6 填充）----
  function setActiveReport(id: string | null) {
    activeReportId.value = id
  }

  function setLanguage(lang: 'zh' | 'en') {
    language.value = lang
  }

  return { activeReportId, language, setActiveReport, setLanguage }
})
