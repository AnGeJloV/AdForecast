import { useEffect, useRef } from 'react'
import type { ReactNode } from 'react'
import { createPortal } from 'react-dom'

/** Стек открытых окон: Escape должен закрывать только верхнее, иначе вместе с ним закроется и то, из которого его открыли. */
const openStack: object[] = []

interface ModalProps {
  open: boolean
  onClose: () => void
  title: string
  subtitle?: string
  wide?: boolean
  children: ReactNode
}

export function Modal({ open, onClose, title, subtitle, wide, children }: ModalProps) {
  const marker = useRef({})

  useEffect(() => {
    if (!open) {
      return
    }
    const self = marker.current
    openStack.push(self)
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape' && openStack[openStack.length - 1] === self) {
        onClose()
      }
    }
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('keydown', onKey)
      const index = openStack.indexOf(self)
      if (index >= 0) {
        openStack.splice(index, 1)
      }
    }
  }, [open, onClose])

  if (!open) {
    return null
  }

  // Портал: внутри <main> (position: relative; z-index: 1) модалка заперта в его контексте
  // наложения, поэтому парящая навигация с z-index 50 рисовалась поверх неё.
  return createPortal(
    <div
      className={`modal-overlay open${wide ? ' wide-overlay' : ''}`}
      onClick={(event) => {
        if (event.target === event.currentTarget) {
          onClose()
        }
      }}
    >
      <div className={`modal${wide ? ' wide' : ''}`}>
        <div className="modal-head">
          <div>
            <h2>{title}</h2>
            {subtitle && <div className="csub">{subtitle}</div>}
          </div>
          <button className="close-x" onClick={onClose} title="Закрыть">✕</button>
        </div>
        {children}
      </div>
    </div>,
    document.body,
  )
}
