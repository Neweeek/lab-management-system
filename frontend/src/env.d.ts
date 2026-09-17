// CSS 副作用导入的类型声明。
// 应用直接在 .ts 里 `import './x.css'`（Vite 会把样式注入产物），
// TypeScript 默认不认识这种导入并报 TS2882，这里显式声明。
declare module '*.css';
declare module '*.scss';
declare module '*.svg' {
  const source: string;
  export default source;
}
