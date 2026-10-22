import { Link } from 'react-router-dom'

export function BrandMark({ size = 26 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 26 26" fill="none" aria-hidden="true">
      <rect x="2" y="14" width="5" height="9" rx="1.5" fill="#F59E0B" />
      <rect x="10.5" y="8" width="5" height="15" rx="1.5" fill="#F59E0B" opacity="0.7" />
      <rect x="19" y="3" width="5" height="20" rx="1.5" fill="#F59E0B" opacity="0.45" />
    </svg>
  )
}

export function Brand({ to }: { to?: string }) {
  const content = (
    <>
      <BrandMark />
      AdForecast
    </>
  )

  if (!to) {
    return <span className="brand">{content}</span>
  }

  return (
    <Link className="brand brand-link" to={to} aria-label="AdForecast">
      {content}
    </Link>
  )
}
