"""
BetterLoka's logo, drawn as SVG: a Loka territory hex in a gold rim, with an L and a plus.

    python3 gen.py                                  # writes icon-*.svg, discord-*.svg, banner-*.svg
    node render.js icon-plus.svg icon.png 1024 1024 transparent
    node render.js discord-plus.svg discord.png 1024 1024
    node render.js banner-plus.svg banner.png 1360 480

render.js uses Playwright's Chromium; the banner's lettering is Inter. The finished pictures are
src/main/resources/assets/betterloka/icon.png, .../textures/gui/logo_<scale>.png, docs/logo.png and
docs/banner.png.
"""
import math, sys

def hex_pts(cx, cy, R):
    return [(cx + R*math.cos(math.radians(a)), cy + R*math.sin(math.radians(a))) for a in (-90,-30,30,90,150,210)]

def rounded(pts, r):
    n=len(pts); d=[]
    side=math.dist(pts[0],pts[1]); t=r/side
    for i in range(n):
        P=pts[i-1]; V=pts[i]; N=pts[(i+1)%n]
        A=(V[0]+(P[0]-V[0])*t, V[1]+(P[1]-V[1])*t)
        B=(V[0]+(N[0]-V[0])*t, V[1]+(N[1]-V[1])*t)
        d.append(("M" if i==0 else "L")+f"{A[0]:.1f},{A[1]:.1f} Q{V[0]:.1f},{V[1]:.1f} {B[0]:.1f},{B[1]:.1f}")
    return " ".join(d)+" Z"

def grid(cx, cy, R, s):
    out=[]
    w=math.sqrt(3)*s
    for j in range(-8,9):
        for i in range(-8,9):
            x=cx+i*w+(j%2)*w/2; y=cy+j*1.5*s
            if math.hypot(x-cx,y-cy) < R+s:
                out.append((x,y))
    return out

TEAM={(-2,2):'#E05A5A',(-1,3):'#E05A5A',(-3,1):'#C94A4A',(2,2):'#5FD37A',(1,3):'#4FBF6A',(3,1):'#9B6BE0',(2,-3):'#E6C34A',(-2,-3):'#4FC3F7',(3,-1):'#9B6BE0'}

def emblem(cx, cy, R, mark):
    """The hex badge, centred at (cx,cy) with circumradius R, in a 1024 design scaled by R/470."""
    k=R/470.0
    def X(v): return cx+(v-512)*k
    def Y(v): return cy+(v-512)*k
    s=[]
    s.append(f'<path d="{rounded(hex_pts(cx,cy,R),58*k)}" fill="#0A0E16"/>')
    s.append(f'<path d="{rounded(hex_pts(cx,cy,R-18*k),52*k)}" fill="url(#gold)"/>')
    s.append(f'<path d="{rounded(hex_pts(cx,cy,R-26*k),50*k)}" fill="none" stroke="#FFF3C4" stroke-opacity="0.55" stroke-width="{6*k:.1f}"/>')
    inner=rounded(hex_pts(cx,cy,R-62*k),40*k)
    s.append(f'<clipPath id="in{int(R)}"><path d="{inner}"/></clipPath>')
    s.append(f'<path d="{inner}" fill="url(#sea)"/>')
    g=[f'<g clip-path="url(#in{int(R)})">']
    hs=64*k; w=math.sqrt(3)*hs
    for j in range(-8,9):
        for i in range(-8,9):
            x=cx+i*w+(j%2)*w/2; y=cy+j*1.5*hs
            if math.hypot(x-cx,y-cy) > R: continue
            col=TEAM.get((i,j))
            pts=" ".join(f"{px:.1f},{py:.1f}" for px,py in hex_pts(x,y,hs-3*k))
            if col:
                g.append(f'<polygon points="{pts}" fill="{col}" fill-opacity="0.30"/>')
            g.append(f'<polygon points="{pts}" fill="none" stroke="#9FD8FF" stroke-opacity="0.10" stroke-width="{3*k:.1f}"/>')
    g.append(f'<path d="{inner}" fill="url(#shine)"/>')
    g.append('</g>')
    s+=g
    s.append(f'<path d="{inner}" fill="none" stroke="#4FC3F7" stroke-opacity="0.85" stroke-width="{9*k:.1f}"/>')
    # The L, with a long shadow.
    L=[(352,330),(482,276),(482,596),(680,596),(680,724),(352,724)]
    def poly(pts,dx=0,dy=0): return " ".join(f"{X(px+dx):.1f},{Y(py+dy):.1f}" for px,py in pts)
    s.append(f'<polygon points="{poly(L,16,18)}" fill="#03070E" fill-opacity="0.6" stroke="#03070E" stroke-opacity="0.6" stroke-width="{26*k:.1f}" stroke-linejoin="round"/>')
    s.append(f'<polygon points="{poly(L)}" fill="url(#white)" stroke="url(#white)" stroke-width="{26*k:.1f}" stroke-linejoin="round"/>')
    # The mark: "+" or a sparkle, gold.
    mx,my=X(664),Y(372)
    if mark=='plus':
        a=104*k; t=36*k
        path=(f"M{mx-t},{my-a} H{mx+t} V{my-t} H{mx+a} V{my+t} H{mx+t} V{my+a} H{mx-t} V{my+t} H{mx-a} V{my-t} H{mx-t} Z")
        s.append(f'<path d="{path}" transform="translate({14*k},{16*k})" fill="#03070E" fill-opacity="0.6" stroke="#03070E" stroke-opacity="0.6" stroke-width="{22*k:.1f}" stroke-linejoin="round"/>')
        s.append(f'<path d="{path}" fill="url(#gold)" stroke="url(#gold)" stroke-width="{22*k:.1f}" stroke-linejoin="round"/>')
    else:
        a=150*k; c=26*k
        path=(f"M{mx},{my-a} C{mx+c},{my-c} {mx+c},{my-c} {mx+a},{my} C{mx+c},{my+c} {mx+c},{my+c} {mx},{my+a} "
              f"C{mx-c},{my+c} {mx-c},{my+c} {mx-a},{my} C{mx-c},{my-c} {mx-c},{my-c} {mx},{my-a} Z")
        s.append(f'<path d="{path}" transform="translate({14*k},{16*k})" fill="#03070E" fill-opacity="0.6"/>')
        s.append(f'<path d="{path}" fill="url(#gold)" stroke="#7A4A0E" stroke-width="{6*k:.1f}"/>')
    return "\n".join(s)

DEFS='''<defs>
<linearGradient id="gold" x1="0" y1="0" x2="0" y2="1">
 <stop offset="0" stop-color="#FFE9A3"/><stop offset="0.45" stop-color="#F2BE4E"/><stop offset="1" stop-color="#B8761E"/></linearGradient>
<linearGradient id="sea" x1="0" y1="0" x2="0.4" y2="1">
 <stop offset="0" stop-color="#1B5C86"/><stop offset="0.55" stop-color="#103A5E"/><stop offset="1" stop-color="#081A30"/></linearGradient>
<linearGradient id="shine" x1="0" y1="0" x2="1" y2="1">
 <stop offset="0" stop-color="#FFFFFF" stop-opacity="0.16"/><stop offset="0.42" stop-color="#FFFFFF" stop-opacity="0.04"/><stop offset="0.43" stop-color="#FFFFFF" stop-opacity="0"/></linearGradient>
<linearGradient id="white" x1="0" y1="0" x2="0" y2="1">
 <stop offset="0" stop-color="#FFFFFF"/><stop offset="1" stop-color="#D3E4F5"/></linearGradient>
<radialGradient id="bg" cx="0.5" cy="0.42" r="0.75">
 <stop offset="0" stop-color="#173B5E"/><stop offset="0.6" stop-color="#0C1D33"/><stop offset="1" stop-color="#070F1C"/></radialGradient>
</defs>'''

def icon(mark, R=470, bg=False, size=1024):
    body=emblem(512,512,R,mark)
    back='<rect width="1024" height="1024" fill="url(#bg)"/>' if bg else ''
    return f'<svg xmlns="http://www.w3.org/2000/svg" width="{size}" height="{size}" viewBox="0 0 1024 1024">{DEFS}{back}{body}</svg>'

def banner(mark):
    W,H=1360,480
    pattern=[]
    hs=46; w=math.sqrt(3)*hs
    for j in range(-1,13):
        for i in range(-1,20):
            x=i*w+(j%2)*w/2; y=j*1.5*hs
            pts=" ".join(f"{px:.1f},{py:.1f}" for px,py in hex_pts(x,y,hs-2))
            op=max(0.0,0.10-0.00009*abs(x-300)) 
            pattern.append(f'<polygon points="{pts}" fill="none" stroke="#9FD8FF" stroke-opacity="{op:.3f}" stroke-width="2"/>')
    body=emblem(250,240,190,mark)
    text=f'''<text x="478" y="262" font-family="Inter" font-weight="900" font-size="128" letter-spacing="-3" fill="#FFFFFF">Better<tspan fill="url(#goldtext)">Loka</tspan></text>
<text x="484" y="330" font-family="Inter" font-weight="600" font-size="36" fill="#9DB3CC">The companion mod for Loka</text>
<text x="484" y="378" font-family="Inter" font-weight="500" font-size="28" fill="#5F7A97">play.lokamc.com</text>'''
    goldtext='<linearGradient id="goldtext" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#FFE9A3"/><stop offset="0.5" stop-color="#F2BE4E"/><stop offset="1" stop-color="#C98623"/></linearGradient>'
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" viewBox="0 0 {W} {H}">'
            f'{DEFS.replace("</defs>", goldtext+"</defs>")}<rect width="{W}" height="{H}" fill="url(#bg)"/>'
            + "".join(pattern) + body + text + '</svg>')

if __name__=='__main__':
    for mark in ('plus','spark'):
        open(f'icon-{mark}.svg','w').write(icon(mark))
        open(f'discord-{mark}.svg','w').write(icon(mark,R=430,bg=True))
        open(f'banner-{mark}.svg','w').write(banner(mark))
