import type { UnwrapRef } from 'vue';
import type { TerminalPreference } from '@/store/modules/terminal/types';
import type { ITerminalChannel, ITerminalSession, TerminalAddons } from '../types/terminal.type';
import { useTerminalStore } from '@/store';
import { fontFamilySuffix, TerminalStatus } from '../types/terminal.const';
import { InputProtocol } from '../types/terminal.protocol';
import { ITerminalOptions, Terminal } from 'xterm';
import { FitAddon } from 'xterm-addon-fit';
import { WebLinksAddon } from 'xterm-addon-web-links';
import { SearchAddon } from 'xterm-addon-search';
import { ImageAddon } from 'xterm-addon-image';
import { CanvasAddon } from 'xterm-addon-canvas';
import { WebglAddon } from 'xterm-addon-webgl';
import { playBell } from '@/utils/bell';
import useCopy from '@/hooks/copy';

const copy = useCopy();

// 终端会话实现
export default class TerminalSession implements ITerminalSession {

  public hostId: number;

  public inst: Terminal;

  public connected: boolean;

  public canWrite: boolean;

  public status: number;

  private readonly sessionId: string;

  private readonly channel: ITerminalChannel;

  private readonly addons: TerminalAddons;

  constructor(hostId: number,
              sessionId: string,
              channel: ITerminalChannel) {
    this.hostId = hostId;
    this.sessionId = sessionId;
    this.channel = channel;
    this.connected = false;
    this.canWrite = false;
    this.status = TerminalStatus.CONNECTING;
    this.inst = undefined as unknown as Terminal;
    this.addons = {} as TerminalAddons;
  }

  // 初始化
  init(dom: HTMLElement): void {
    const { preference } = useTerminalStore();
    // 初始化实例
    this.inst = new Terminal({
      ...(preference.displaySetting as any),
      theme: preference.theme.schema,
      fastScrollModifier: !!preference.interactSetting.fastScrollModifier ? 'alt' : 'none',
      altClickMovesCursor: !!preference.interactSetting.altClickMovesCursor,
      rightClickSelectsWord: !!preference.interactSetting.rightClickSelectsWord,
      fontFamily: preference.displaySetting.fontFamily + fontFamilySuffix,
      wordSeparator: preference.interactSetting.wordSeparator,
      scrollback: preference.sessionSetting.scrollBackLine,
    });
    // 注册快捷键
    // 注册事件
    this.registerEvent(dom, preference);
    // 注册插件
    this.registerAddions(preference);
    // 打开终端
    this.inst.open(dom);
    // 自适应
    this.addons.fit.fit();
  }

  // 注册事件
  private registerEvent(dom: HTMLElement, preference: UnwrapRef<TerminalPreference>) {
    // 注册输入事件
    this.inst.onData(s => {
      if (!this.canWrite || !this.connected) {
        return;
      }
      // 输入
      this.channel.send(InputProtocol.INPUT, {
        sessionId: this.sessionId,
        command: s
      });
    });
    // 启用响铃
    if (preference.interactSetting.enableBell) {
      this.inst.onBell(() => {
        // 播放蜂鸣
        playBell();
      });
    }
    // 选中复制
    if (preference.interactSetting.selectionChangeCopy) {
      this.inst.onSelectionChange(() => {
        // 复制选中内容
        this.copySelection();
      });
    }
    // 注册 resize 事件
    this.inst.onResize(({ cols, rows }) => {
      if (!this.connected) {
        return;
      }
      this.channel.send(InputProtocol.RESIZE, {
        sessionId: this.sessionId,
        cols,
        rows
      });
    });
    // 设置右键选项
    dom.addEventListener('contextmenu', async (event) => {
      // 如果开启了右键粘贴 右键选中 右键菜单 则关闭默认右键菜单
      if (preference.interactSetting.rightClickSelectsWord
        || preference.interactSetting.rightClickPaste
        || preference.interactSetting.enableRightClickMenu) {
        event.preventDefault();
      }
      // 右键粘贴逻辑
      if (preference.interactSetting.rightClickPaste) {
        if (!this.canWrite || !this.connected) {
          return;
        }
        // 未开启右键选中 || 开启并无选中的内容则粘贴
        if (!preference.interactSetting.rightClickSelectsWord || !this.inst.hasSelection()) {
          this.pasteTrimEnd(await copy.readText());
        }
      }
    });
  }

  // 注册插件
  private registerAddions(preference: UnwrapRef<TerminalPreference>) {
    this.addons.fit = new FitAddon();
    this.addons.search = new SearchAddon();
    // 超链接插件
    if (preference.pluginsSetting.enableWeblinkPlugin) {
      this.addons.weblink = new WebLinksAddon();
    }
    if (preference.pluginsSetting.enableWebglPlugin) {
      // WebGL 渲染插件
      this.addons.webgl = new WebglAddon();
    } else {
      // canvas 渲染插件
      this.addons.canvas = new CanvasAddon();
    }
    // 图片渲染插件
    if (preference.pluginsSetting.enableImagePlugin) {
      this.addons.image = new ImageAddon();
    }
    for (const addon of Object.values(this.addons)) {
      this.inst.loadAddon(addon);
    }
  }

  // 设置已连接
  connect(): void {
    this.status = TerminalStatus.CONNECTED;
    this.connected = true;
    this.inst.focus();
  }

  // 设置是否可写
  setCanWrite(canWrite: boolean): void {
    this.canWrite = canWrite;
    if (canWrite) {
      this.inst.options.cursorBlink = useTerminalStore().preference.displaySetting.cursorBlink;
    } else {
      this.inst.options.cursorBlink = false;
    }
  }

  // 写入数据
  write(value: string | Uint8Array): void {
    this.inst.write(value);
  }

  // 自适应
  fit(): void {
    this.addons.fit?.fit();
  }

  // 聚焦
  focus(): void {
    this.inst.focus();
  }

  // 清空
  clear(): void {
    this.inst.clear();
    this.inst.clearSelection();
    this.inst.focus();
  }

  // 粘贴
  paste(value: string): void {
    this.inst.paste(value);
    this.inst.focus();
  }

  // 粘贴并且去除尾部空格 (如果配置)
  pasteTrimEnd(value: string): void {
    if (useTerminalStore().preference.interactSetting.pasteAutoTrim) {
      // 粘贴前去除尾部空格
      this.inst.paste(value.trimEnd());
    } else {
      this.inst.paste(value);
    }
    this.inst.focus();
  }

  // 选中全部
  selectAll(): void {
    this.inst.selectAll();
    this.inst.focus();
  }

  // 复制选中
  copySelection(): string {
    let selection = this.inst.getSelection();
    if (selection) {
      // 去除尾部空格
      const { preference } = useTerminalStore();
      if (preference.interactSetting.copyAutoTrim) {
        selection = selection.trimEnd();
      }
      // 复制
      copy.copy(selection, false);
    }
    // 聚焦
    this.inst.focus();
    return selection;
  }

  // 去顶部
  toTop(): void {
    this.inst.scrollToTop();
    this.inst.focus();
  }

  // 去底部
  toBottom(): void {
    this.inst.scrollToBottom();
    this.inst.focus();
  }

  // 获取配置
  getOption(option: string): any {
    return this.inst.options[option as keyof ITerminalOptions] as any;
  }

  // 设置配置
  setOption(option: string, value: any): void {
    this.inst.options[option as keyof ITerminalOptions] = value;
  }

  // 断开连接
  disconnect(): void {
    // 发送关闭消息
    this.channel.send(InputProtocol.CLOSE, {
      sessionId: this.sessionId
    });
  }

  // 关闭
  close(): void {
    try {
      // 卸载插件
      Object.values(this.addons)
        .filter(Boolean)
        .forEach(s => s.dispose());
      // 卸载实体
      this.inst.dispose();
    } catch (e) {
    }
  }

}