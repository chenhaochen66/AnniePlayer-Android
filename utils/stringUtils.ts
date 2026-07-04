export function decodeHtmlEntities(text: string): string {
  return text
    .replace(/&nbsp;/g, ' ')
    .replace(/&amp;/g, '&')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"')
    .replace(/&#39;/g, "'")
    .replace(/&apos;/g, "'")
    .replace(/\\u0026/g, '&')
    .replace(/\\u0020/g, ' ')
    .replace(/\\&/g, '&')
    .replace(/_+u0026/g, '&')
    .replace(/_+u0020/g, ' ');
}
