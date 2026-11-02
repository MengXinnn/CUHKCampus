// Keep code literal while accepting the LaTeX delimiters commonly emitted by models.
export function mathMarkdown(text: string): string {
  return text.replace(
    /(^ {0,3}(`{3,}|~{3,})[^\n]*\n[\s\S]*?^ {0,3}\2[^\n]*$)|(`+)[\s\S]*?\3|\\\[([\s\S]*?)\\\]|\\\(([\s\S]*?)\\\)/gm,
    (match, codeBlock, _fence, inlineCode, display, inline) => {
      if (codeBlock || inlineCode) return match;
      return display !== undefined ? `\n\n$$\n${display.trim()}\n$$\n\n` : `$${inline}$`;
    },
  );
}
