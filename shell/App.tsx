/**
 * [INPUT]: 依赖官方 nsh host.call/on 与 平台宿主提供的无凭据加载阶段
 * [OUTPUT]: 只读全屏启动/恢复说明，不提供运行时操作，也不接收端口、凭据或内部错误
 * [POS]: mobile NSH 加载遮罩，真实 DSH 主页面提交后由平台页面owner隐藏
 * [PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
 */
import { mount, signal, Text, View, host } from "nsh";

type Phase = "starting" | "recovering" | "interrupted" | "waiting" | "ready";

function parsePhase(value: unknown): Phase {
  if (typeof value === "object" && value !== null && "phase" in value) {
    if (value.phase === "starting" || value.phase === "recovering" || value.phase === "interrupted" || value.phase === "waiting" || value.phase === "ready") return value.phase;
  }
  return "recovering";
}

function App() {
  const phase = signal<Phase>("starting");
  const accept = (value: unknown) => phase.set(parsePhase(value));
  host.on("runtime.state", accept);
  host.call("runtime.status").then(accept, () => phase.set("recovering"));
  const label = () => {
    switch (phase()) {
      case "starting": return "正在准备你的工作区…";
      case "recovering": return "正在恢复工作区，请稍候…";
      case "waiting": return "工作区暂时不可用，稍后将自动重试。";
      case "interrupted": return "工作区暂时无法恢复，返回应用后会继续尝试。";
      case "ready": return "工作区已就绪";
    }
  };
  return <View style={{ flex: 1, padding: 28, gap: 14, justifyContent: "center", alignItems: "center", backgroundColor: "#f6f7f8" }}>
    <Text style={{ fontSize: 26, fontWeight: "bold", color: "#1f2328" }}>OwnDsh</Text>
    <Text style={{ fontSize: 14, color: "#525964" }}>{label}</Text>
  </View>;
}
mount(App);
