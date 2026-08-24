import { defineConfig } from 'vitepress'

export default defineConfig({
  title: "Ping'd",
  description: "A persistent Android status-bar icon showing your uplink (internet) connectivity health.",
  base: '/Pingd/',
  themeConfig: {
    nav: [
      { text: 'Home', link: '/' },
      {
        text: 'Guide',
        items: [
          { text: 'Install and setup', link: '/guide/install' },
          { text: 'The status-bar icon', link: '/guide/status-icon' },
          { text: 'The settings screen', link: '/guide/settings' },
          { text: 'Troubleshooting', link: '/guide/troubleshooting' },
        ],
      },
      { text: 'Privacy Policy', link: '/privacy' },
      { text: 'GitHub', link: 'https://github.com/ScottKirvan/Pingd' }
    ],
    sidebar: [
      {
        text: 'Guide',
        items: [
          { text: 'Install and setup', link: '/guide/install' },
          { text: 'The status-bar icon', link: '/guide/status-icon' },
          { text: 'The settings screen', link: '/guide/settings' },
          { text: 'Troubleshooting', link: '/guide/troubleshooting' },
        ],
      },
    ],
    socialLinks: [
      { icon: 'github', link: 'https://github.com/ScottKirvan/Pingd' },
      { icon: 'discord', link: 'https://discord.gg/TN6XJSNK5Y' }
    ],
    footer: {
      message: 'Released under the MIT License.',
      copyright: 'Copyright © Scott Kirvan'
    }
  }
})
