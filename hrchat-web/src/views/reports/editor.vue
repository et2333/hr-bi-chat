<template>
  <div class="editor-page">
    <a-card :bordered="false" class="editor-card">
      <a-page-header title="新建报表" @back="router.push('/reports')">
        <template #backIcon><ArrowLeftOutlined /></template>
      </a-page-header>

      <a-form layout="vertical" class="editor-form">
        <a-form-item label="来源" required>
          <a-radio-group v-model:value="sourceType">
            <a-radio value="TEMPLATE">模板实例化</a-radio>
            <a-radio value="CUSTOM">自定义</a-radio>
          </a-radio-group>
        </a-form-item>

        <a-form-item label="报表名称" required>
          <a-input v-model:value="name" placeholder="如：研发中心人力月报" :maxlength="64" />
        </a-form-item>

        <template v-if="sourceType === 'TEMPLATE'">
          <a-form-item label="选择模板" required>
            <a-select v-model:value="templateId" placeholder="选择模板">
              <a-select-option v-for="tpl in templates" :key="tpl.id" :value="tpl.id">
                {{ tpl.name }}（{{ tpl.category }}）
              </a-select-option>
            </a-select>
          </a-form-item>
          <div v-if="templateDetail" class="template-info">
            <p class="template-desc">{{ templateDetail.description }}</p>
            <a-space wrap>
              <a-tag v-for="(desc, key) in templateDetail.paramsSchema" :key="key" color="blue">
                {{ key }}：{{ desc }}
              </a-tag>
            </a-space>
          </div>
          <a-form-item label="组织 ID（org_id）">
            <a-input v-model:value="params.org_id" placeholder="如 RD（研发中心）" />
          </a-form-item>
          <a-form-item label="统计月份（period）">
            <a-input v-model:value="params.period" placeholder="如 2026-08，缺省最近完整月" />
          </a-form-item>
        </template>

        <template v-else>
          <a-form-item label="组件定义（语义层对象，JSON）">
            <a-textarea
              v-model:value="customComponents"
              :rows="6"
              placeholder='[{"compType":"METRIC_CARD","chartType":"BAR","def":{"metric":"headcount","org":"RD"}}]'
            />
          </a-form-item>
        </template>

        <a-form-item label="自动刷新">
          <a-select v-model:value="refreshFrequency" style="width: 160px">
            <a-select-option value="DAILY">每日</a-select-option>
            <a-select-option value="WEEKLY">每周</a-select-option>
            <a-select-option value="MONTHLY">每月</a-select-option>
            <a-select-option value="NONE">不刷新</a-select-option>
          </a-select>
        </a-form-item>

        <a-form-item>
          <a-button type="primary" :loading="submitting" @click="submit">
            创建报表
          </a-button>
        </a-form-item>
      </a-form>
    </a-card>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { ArrowLeftOutlined } from '@ant-design/icons-vue'
import { reportApi } from '@/api'
import type { ComponentSpec } from '@/api/reports'
import type { TemplateDetail, TemplateItem } from '@/api/types'

const route = useRoute()
const router = useRouter()

const templates = ref<TemplateItem[]>([])
const templateDetail = ref<TemplateDetail | null>(null)
const sourceType = ref<'TEMPLATE' | 'CUSTOM'>('TEMPLATE')
const name = ref('')
const templateId = ref<string | null>((route.query.template as string) || null)
const params = ref<Record<string, string>>({ org_id: '', period: '' })
const customComponents = ref('')
const refreshFrequency = ref('DAILY')
const submitting = ref(false)

onMounted(async () => {
  const res = await reportApi.listTemplates('', 1, 50)
  templates.value = res.data.records
  if (templateId.value) await loadTemplateDetail(templateId.value)
})

watch(templateId, (id) => {
  if (id) loadTemplateDetail(id)
})

async function loadTemplateDetail(id: string) {
  try {
    const res = await reportApi.getTemplate(id)
    templateDetail.value = res.data
  } catch {
    templateDetail.value = null
  }
}

async function submit() {
  if (!name.value.trim()) {
    message.warning('请填写报表名称')
    return
  }
  submitting.value = true
  try {
    let reportId: number
    if (sourceType.value === 'TEMPLATE') {
      if (!templateId.value) {
        message.warning('请选择模板')
        return
      }
      reportId = (await reportApi.instantiateTemplate(
        templateId.value,
        {
          org_id: params.value.org_id,
          period: params.value.period || undefined,
        },
        name.value,
      )).data
    } else {
      let components: ComponentSpec[] = []
      if (customComponents.value.trim()) {
        try {
          components = JSON.parse(customComponents.value) as ComponentSpec[]
        } catch {
          message.error('组件 JSON 格式错误')
          return
        }
      }
      reportId = (await reportApi.createReport({
        sourceType: 'CUSTOM',
        name: name.value.trim(),
        refresh: refreshFrequency.value === 'NONE' ? undefined : { frequency: refreshFrequency.value, time: '08:00' },
        components,
      })).data
    }
    message.success('创建成功')
    router.push(`/reports/${reportId}`)
  } catch (e) {
    message.error(e instanceof Error ? e.message : '创建失败')
  } finally {
    submitting.value = false
  }
}
</script>

<style scoped>
.editor-card {
  max-width: 720px;
  margin: 0 auto;
}

.editor-form {
  margin-top: 16px;
}

.template-info {
  margin-bottom: 16px;
  padding: 12px;
  background: #fafafa;
  border-radius: 6px;
}

.template-desc {
  margin: 0 0 8px;
  font-size: 13px;
  color: rgba(0, 0, 0, 0.65);
}
</style>
