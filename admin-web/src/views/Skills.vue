<template>
  <div class="skills-container">
    <div class="toolbar">
      <h2>技能管理</h2>
      <el-button type="primary" @click="handleAdd" :icon="Plus">新增技能</el-button>
    </div>

    <el-table :data="skills" style="width: 100%" v-loading="loading">
      <el-table-column prop="name" label="名称" width="180">
        <template #default="scope">
          <span>{{ scope.row.name }}</span>
          <el-tag v-if="scope.row.is_builtin" type="info" size="small" style="margin-left: 8px">内置</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="description" label="描述" show-overflow-tooltip />
      <el-table-column prop="owner_device_id" label="设备ID" width="150" />
      <el-table-column label="启用" width="100">
        <template #default="scope">
          <el-switch v-model="scope.row.is_active" disabled />
        </template>
      </el-table-column>
      <el-table-column label="操作" width="180" fixed="right">
        <template #default="scope">
          <el-button
            size="small"
            @click="handleEdit(scope.row)"
            :disabled="scope.row.is_builtin || !scope.row.owner_device_id"
            :title="scope.row.is_builtin ? '内置技能为只读' : scope.row.owner_device_id ? '' : '缺少设备ID，无法编辑'"
          >
            编辑
          </el-button>
          <el-button
            size="small"
            type="danger"
            @click="handleDelete(scope.row)"
            :disabled="scope.row.is_builtin || !scope.row.owner_device_id"
            :title="scope.row.is_builtin ? '无法删除内置技能' : scope.row.owner_device_id ? '' : '缺少设备ID，无法删除'"
          >
            删除
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialogVisible" :title="isEdit ? '编辑技能' : '新增技能'" width="600px">
      <el-form ref="formRef" :model="form" label-width="120px" :rules="rules">
        <el-form-item label="名称" prop="name">
          <el-input v-model="form.name" />
        </el-form-item>
        <el-form-item label="设备ID" prop="owner_device_id">
          <el-input v-model="form.owner_device_id" placeholder="留空表示全局技能" />
        </el-form-item>
        <el-form-item label="描述" prop="description">
          <el-input v-model="form.description" type="textarea" />
        </el-form-item>
        <el-form-item label="定义" prop="definitionStr">
          <el-input
            v-model="form.definitionStr"
            type="textarea"
            :rows="6"
            placeholder="JSON 格式的技能定义"
          />
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="form.is_active" />
        </el-form-item>
      </el-form>
      <template #footer>
        <span class="dialog-footer">
          <el-button @click="dialogVisible = false">取消</el-button>
          <el-button type="primary" @click="submitForm" :loading="submitting">确定</el-button>
        </span>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import type { FormInstance } from 'element-plus';
import { Plus } from '@element-plus/icons-vue';
import { getSkills, createSkill, updateSkill, deleteSkill } from '@/api';
import type { Skill } from '@/api/types';

const skills = ref<Skill[]>([]);
const loading = ref(false);
const dialogVisible = ref(false);
const isEdit = ref(false);
const submitting = ref(false);
const formRef = ref<FormInstance>();

// 技能表单，增加 definitionStr 用于编辑 JSON
interface SkillForm extends Partial<Skill> {
  definitionStr: string;
}

const form = reactive<SkillForm>({
  name: '',
  description: '',
  owner_device_id: '',
  is_active: true,
  definitionStr: '{}',
});

// 表单验证规则
const rules = {
  name: [{ required: true, message: '请输入技能名称', trigger: 'blur' }],
  definitionStr: [{ required: true, message: '请输入技能定义 JSON', trigger: 'blur' }],
};

// 获取技能列表
const fetchSkills = async () => {
  loading.value = true;
  try {
    const res = await getSkills();
    skills.value = res.data;
  } catch (error) {
    ElMessage.error('获取技能列表失败');
  } finally {
    loading.value = false;
  }
};

// 新增技能
const handleAdd = () => {
  isEdit.value = false;
  Object.assign(form, {
    name: '',
    description: '',
    owner_device_id: '',
    is_active: true,
    definitionStr: '{}',
    id: undefined
  });
  dialogVisible.value = true;
};

// 编辑技能
const handleEdit = (row: Skill) => {
  isEdit.value = true;
  Object.assign(form, row);
  form.definitionStr = JSON.stringify(row.definition, null, 2);
  dialogVisible.value = true;
};

// 删除技能
const handleDelete = async (row: Skill) => {
  if (!row.owner_device_id) {
    ElMessage.warning('无法删除没有设备ID的技能');
    return;
  }
  try {
    await ElMessageBox.confirm('确定要删除该技能吗？', '警告', { type: 'warning' });
    await deleteSkill(row.id, row.owner_device_id);
    ElMessage.success('删除成功');
    fetchSkills();
  } catch (error) {
    if (error === 'cancel' || error === 'close') return;
    ElMessage.error('删除失败');
  }
};

// 提交表单
const submitForm = async () => {
  if (!formRef.value) return;

  try {
    await formRef.value.validate();
  } catch {
    return;
  }

  submitting.value = true;
  try {
    const definition = JSON.parse(form.definitionStr);
    const payload: any = {
      name: form.name,
      description: form.description,
      is_active: form.is_active,
      definition,
    };
    // 仅在有值时才包含 owner_device_id
    if (form.owner_device_id) {
      payload.owner_device_id = form.owner_device_id;
    }

    if (isEdit.value && form.id && form.owner_device_id) {
      await updateSkill(form.id, payload, form.owner_device_id);
      ElMessage.success('更新成功');
    } else {
      await createSkill(payload);
      ElMessage.success('创建成功');
    }
    dialogVisible.value = false;
    fetchSkills();
  } catch (error) {
    ElMessage.error('操作失败，请检查 JSON 格式');
  } finally {
    submitting.value = false;
  }
};

onMounted(fetchSkills);
</script>

<style scoped>
.skills-container {
  padding: 20px;
}
.toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
}
</style>
