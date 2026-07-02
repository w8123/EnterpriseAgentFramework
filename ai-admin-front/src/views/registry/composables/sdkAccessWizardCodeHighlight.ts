export function escapeHtml(value: string) {
  return value
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
}

export function highlightXmlCode(value: string) {
  return escapeHtml(value).replace(
    /(&lt;\/?)([\w.-]+)(&gt;)|([A-Za-z0-9_.:-]+)(?=&lt;\/[\w.-]+&gt;)/g,
    (token, tagOpen, tagName, tagClose, textValue) => {
      if (tagName) {
        return `<span class="code-punctuation">${tagOpen}</span><span class="code-tag">${tagName}</span><span class="code-punctuation">${tagClose}</span>`
      }
      if (textValue) return `<span class="code-string">${textValue}</span>`
      return token
    },
  )
}

export function highlightYamlCode(value: string) {
  return value
    .split('\n')
    .map((line) => {
      const match = /^(\s*)([A-Za-z0-9_.-]+)(:)(.*)$/.exec(line)
      if (!match) return escapeHtml(line)

      const [, indent, key, colon, rawRest] = match
      const rest = escapeHtml(rawRest).replace(
        /(\$\{[^}]+\})|\b(true|false)\b|\b(https?:\/\/[^\s<}]+)\b/g,
        (token, envToken, booleanToken, urlToken) => {
          if (envToken) return `<span class="code-env">${envToken}</span>`
          if (booleanToken) return `<span class="code-boolean">${booleanToken}</span>`
          if (urlToken) return `<span class="code-url">${urlToken}</span>`
          return token
        },
      )

      return `${escapeHtml(indent)}<span class="code-key">${key}</span><span class="code-punctuation">${colon}</span>${rest}`
    })
    .join('\n')
}

export function highlightJsCode(value: string) {
  const escaped = escapeHtml(value)
  const tokenPattern = /(\/\/[^\n]*|'[^'\n]*'|"[^"\n]*")|\b(import|from|const|async|await|return|if|throw|new)\b|\b(createEafChat|URLSearchParams|fetch|Error)\b|([A-Za-z_$][\w$]*)(?=\s*:)|([A-Za-z_$][\w$]*)(?=\()|(\|\||=>|[{}()[\],.:?])/g

  return escaped.replace(
    tokenPattern,
    (token, literalToken, keywordToken, functionToken, propertyToken, callToken, punctuationToken) => {
      if (literalToken?.startsWith('//')) return `<span class="code-comment">${literalToken}</span>`
      if (literalToken) return `<span class="code-string">${literalToken}</span>`
      if (keywordToken) return `<span class="code-keyword">${keywordToken}</span>`
      if (functionToken || callToken) return `<span class="code-function">${functionToken || callToken}</span>`
      if (propertyToken) return `<span class="code-property">${propertyToken}</span>`
      if (punctuationToken) return `<span class="code-punctuation">${punctuationToken}</span>`
      return token
    },
  )
}
