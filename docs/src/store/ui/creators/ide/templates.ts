/**
 * The plugins to start from (PluginTemplates.kt): the blank one, and the demo
 * plugins the documentation teaches from, read straight from the app's assets
 * so the web editor never carries a stale copy.
 */
import cipher from '../../../../../../app/src/main/assets/plugin-templates/cipher-tool.lua?raw';
import textTools from '../../../../../../app/src/main/assets/plugin-templates/text-tools.lua?raw';
import todo from '../../../../../../app/src/main/assets/plugin-templates/todo-list.lua?raw';
import kitchenSink from '../../../../../../app/src/main/assets/plugin-templates/ui-kitchen-sink.lua?raw';
import { BLANK_PLUGIN_SCRIPT } from './ide-store';
import { S } from './strings';

export type PluginTemplateId = 'BLANK' | 'TEXT_TOOLS' | 'CIPHER' | 'TODO' | 'KITCHEN_SINK';

export interface PluginTemplate {
	id: PluginTemplateId;
	name: string;
	description: string;
	script: string;
	/** Whether the script uses wm.storage, so the new manifest declares it. */
	storage: boolean;
}

export const TEMPLATES: PluginTemplate[] = [
	{ id: 'BLANK', name: S.templateBlankName, description: S.templateBlankDescription, script: BLANK_PLUGIN_SCRIPT, storage: false },
	{ id: 'TEXT_TOOLS', name: S.templateTextToolsName, description: S.templateTextToolsDescription, script: textTools, storage: false },
	{ id: 'CIPHER', name: S.templateCipherName, description: S.templateCipherDescription, script: cipher, storage: false },
	{ id: 'TODO', name: S.templateTodoName, description: S.templateTodoDescription, script: todo, storage: true },
	{ id: 'KITCHEN_SINK', name: S.templateKitchenSinkName, description: S.templateKitchenSinkDescription, script: kitchenSink, storage: false },
];
