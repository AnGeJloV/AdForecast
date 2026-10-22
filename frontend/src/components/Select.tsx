import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import type { KeyboardEvent as ReactKeyboardEvent } from 'react'

export interface SelectOption {
  value: string
  label: string
}

interface SelectProps {
  value: string
  options: SelectOption[]
  onChange: (value: string) => void
  placeholder?: string
  variant?: 'pill' | 'field' | 'compact'
  disabled?: boolean
}

/** Границы ближайшего прокручиваемого контейнера (модалка и т. п.) — иначе список обрезается родителем. */
function clippingBounds(node: HTMLElement): { top: number; bottom: number } {
  let current = node.parentElement
  while (current) {
    const overflow = window.getComputedStyle(current).overflowY
    if (overflow === 'auto' || overflow === 'scroll' || overflow === 'hidden') {
      const rect = current.getBoundingClientRect()
      return { top: rect.top, bottom: rect.bottom }
    }
    current = current.parentElement
  }
  return { top: 0, bottom: window.innerHeight }
}

/** Выпадающий список в теме приложения: нативное меню <select> рисует ОС и не поддаётся оформлению. */
export function Select({ value, options, onChange, placeholder = 'не выбрано', variant = 'field', disabled }: SelectProps) {
  const [open, setOpen] = useState(false)
  const [active, setActive] = useState(0)
  const [placement, setPlacement] = useState({ up: false, maxHeight: 300 })
  const root = useRef<HTMLDivElement>(null)
  const list = useRef<HTMLUListElement>(null)

  const selectedIndex = options.findIndex((option) => option.value === value)
  const selected = selectedIndex >= 0 ? options[selectedIndex] : null

  const openList = () => {
    setActive(selectedIndex >= 0 ? selectedIndex : 0)
    setOpen(true)
  }

  useEffect(() => {
    if (!open) {
      return
    }
    const onPointerDown = (event: MouseEvent) => {
      if (!root.current?.contains(event.target as Node)) {
        setOpen(false)
      }
    }
    document.addEventListener('mousedown', onPointerDown)
    return () => document.removeEventListener('mousedown', onPointerDown)
  }, [open])

  useLayoutEffect(() => {
    if (!open || !root.current) {
      return
    }
    const rect = root.current.getBoundingClientRect()
    const bounds = clippingBounds(root.current)
    const below = bounds.bottom - rect.bottom - 16
    const above = rect.top - bounds.top - 16
    const up = below < 220 && above > below
    setPlacement({ up, maxHeight: Math.max(150, Math.min(320, up ? above : below)) })
  }, [open])

  useEffect(() => {
    if (!open) {
      return
    }
    const node = list.current?.children[active] as HTMLElement | undefined
    node?.scrollIntoView({ block: 'nearest' })
  }, [active, open])

  const choose = (index: number) => {
    const option = options[index]
    if (!option) {
      return
    }
    onChange(option.value)
    setOpen(false)
  }

  const onKeyDown = (event: ReactKeyboardEvent<HTMLButtonElement>) => {
    if (disabled) {
      return
    }
    switch (event.key) {
      case 'ArrowDown':
        event.preventDefault()
        open ? setActive((current) => Math.min(current + 1, options.length - 1)) : openList()
        break
      case 'ArrowUp':
        event.preventDefault()
        open ? setActive((current) => Math.max(current - 1, 0)) : openList()
        break
      case 'Home':
        if (open) {
          event.preventDefault()
          setActive(0)
        }
        break
      case 'End':
        if (open) {
          event.preventDefault()
          setActive(options.length - 1)
        }
        break
      case 'Enter':
      case ' ':
        event.preventDefault()
        open ? choose(active) : openList()
        break
      case 'Escape':
        if (open) {
          event.preventDefault()
          setOpen(false)
        }
        break
      case 'Tab':
        setOpen(false)
        break
      default:
        break
    }
  }

  return (
    <div className={`dd dd-${variant}${open ? ' open' : ''}${disabled ? ' disabled' : ''}`} ref={root}>
      <button
        type="button"
        className="dd-btn"
        disabled={disabled}
        aria-haspopup="listbox"
        aria-expanded={open}
        onClick={() => (open ? setOpen(false) : openList())}
        onKeyDown={onKeyDown}
      >
        <span className={`dd-value${selected ? '' : ' empty'}`}>{selected ? selected.label : placeholder}</span>
        <svg className="dd-chev" width="10" height="6" viewBox="0 0 10 6" aria-hidden="true">
          <path d="M1 1l4 4 4-4" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" />
        </svg>
      </button>
      {open && (
        <ul
          className={`dd-panel${placement.up ? ' up' : ''}`}
          role="listbox"
          ref={list}
          style={placement.up
            ? { bottom: '100%', maxHeight: placement.maxHeight }
            : { top: '100%', maxHeight: placement.maxHeight }}
        >
          {options.length === 0 && <li className="dd-opt empty">нет вариантов</li>}
          {options.map((option, index) => (
            <li
              key={option.value}
              role="option"
              aria-selected={option.value === value}
              className={`dd-opt${index === active ? ' active' : ''}${option.value === value ? ' selected' : ''}`}
              onMouseEnter={() => setActive(index)}
              onMouseDown={(event) => { event.preventDefault(); choose(index) }}
            >
              {option.label}
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}
