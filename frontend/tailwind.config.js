/** @type {import('tailwindcss').Config} */
export default {
  content: ["./index.html", "./src/**/*.{js,ts,jsx,tsx}"],
  theme: {
    extend: {
      // 品牌调色板：结构上仍是一套语义槽位（canvas/border/fg/accent/green/red），
      // 但色相从 GitHub Primer 的冷灰蓝整体转成"鼠尾草青绿 + 暖燕麦底"——
      // 契合亲子产品的温润气质。规矩不变：**所有灰阶/绿阶只从这一套里取**，
      // 散写"差不多的颜色"拼出来就是仿品感。
      colors: {
        gh: {
          canvas: '#f7f6f1',            // 页面底：暖燕麦白（根节点另叠一层 from/via/to 柔光渐变）
          border: { DEFAULT: '#d7ddd4', muted: '#e1e6dd' },  // 低饱和淡青绿描边
          fg: { DEFAULT: '#1f2328', muted: '#59636e', faint: '#818b98' },
          accent: '#4e7d5f',            // 链接/聚焦/选中（原 Primer 蓝 → 鼠尾草深绿）
          'accent-soft': '#e4efe3',     // 强调底色（用户气泡、徽标）
          green: { DEFAULT: '#5b8f6e', hover: '#4d7f5f' },  // 主按钮基色（渐变在组件层叠加）
          red: '#d1242f',
        },
      },
      backgroundImage: {
        // 主按钮的"鼠尾草 → 森林青"微渐变：发送、生成行程共用这一个，
        // 渐变只此一份，两颗按钮才像一家人
        'sage-gradient': 'linear-gradient(135deg, #85ab8b 0%, #4f8a68 100%)',
      },
      fontFamily: {
        // 衬线只给"叙事"用：品牌名、大字问候、每天的"第 N 天"。
        // 排版语言学的：衬线=内容/杂志感，无衬线=界面/工具感 —— 一个页面两种字体分工，
        // 比全站换衬线克制得多。Windows 兜底宋体，macOS/移动端有宋体-简。
        serif: ['Georgia', '"Noto Serif SC"', '"Source Han Serif SC"', '"Songti SC"', 'STSong', 'SimSun', 'serif'],
      },
      // 生成行程时的等待动画。只有两个，而且**没有一个表示"完成到百分之几"** ——
      // 五步的耗时完全不可预测（模型那两段在 10~30 秒之间浮动），
      // 画一条会走到头的进度条就是在编，理由见 src/types/plan.ts 里 PlanProgress 那段。
      keyframes: {
        // 不确定态的扫光：一道高光在轨道上来回走，意思是"在动，但不知道还要多久"。
        // 这正是我们要表达的东西 —— 不假装知道终点在哪。
        sweep: {
          '0%': { transform: 'translateX(-100%)' },
          '100%': { transform: 'translateX(300%)' },
        },
        // 当前那一步的呼吸。只改透明度、不动尺寸 ——
        // 改尺寸会让整块面板跟着一缩一放，读起来很吵。
        breathe: {
          '0%, 100%': { opacity: '1' },
          '50%': { opacity: '0.35' },
        },
      },
      animation: {
        sweep: 'sweep 1.4s ease-in-out infinite',
        breathe: 'breathe 1.6s ease-in-out infinite',
      },
    },
  },
  plugins: [],
}
