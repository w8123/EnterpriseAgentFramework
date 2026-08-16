import { afterEach, describe, expect, it, vi } from 'vitest'
import { copyAiCodingText } from './aiCodingClipboard'

describe('copyAiCodingText', () => {
  afterEach(() => {
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
    Reflect.deleteProperty(document, 'execCommand')
  })

  it('uses the browser Clipboard API when available', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    vi.stubGlobal('navigator', { clipboard: { writeText } })

    await expect(copyAiCodingText('handoff text')).resolves.toEqual({
      copied: true,
      method: 'clipboard',
    })
    expect(writeText).toHaveBeenCalledWith('handoff text')
  })

  it('falls back to a selected textarea when clipboard permission is denied', async () => {
    const writeText = vi.fn().mockRejectedValue(new Error('denied'))
    vi.stubGlobal('navigator', { clipboard: { writeText } })
    const execCommand = vi.fn().mockReturnValue(true)
    Object.defineProperty(document, 'execCommand', {
      configurable: true,
      value: execCommand,
    })

    await expect(copyAiCodingText('handoff text')).resolves.toEqual({
      copied: true,
      method: 'legacy',
    })
    expect(execCommand).toHaveBeenCalledWith('copy')
    expect(document.querySelectorAll('textarea')).toHaveLength(0)
  })

  it('keeps the text available for manual copy when neither browser mechanism works', async () => {
    vi.stubGlobal('navigator', {})
    Object.defineProperty(document, 'execCommand', {
      configurable: true,
      value: vi.fn().mockReturnValue(false),
    })

    await expect(copyAiCodingText('handoff text')).resolves.toEqual({
      copied: false,
      method: 'manual',
    })
  })
})
