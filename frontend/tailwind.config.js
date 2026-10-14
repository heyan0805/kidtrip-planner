/** @type {import('tailwindcss').Config} */
export default {
  content: ["./index.html", "./src/**/*.{js,ts,jsx,tsx}"],
  theme: {
    extend: {
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
