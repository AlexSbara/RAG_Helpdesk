import { LOCALE_ID } from '@angular/core';
import { bootstrapApplication } from '@angular/platform-browser';
import { provideHttpClient } from '@angular/common/http';
import { registerLocaleData } from '@angular/common';
import localeIt from '@angular/common/locales/it';
import { ChatComponent } from './app/chat.component';

// Locale italiano: punteggi e tempi vengono formattati con la virgola decimale
registerLocaleData(localeIt);

// Avvio dell'applicazione con il componente chat come radice.
bootstrapApplication(ChatComponent, {
  providers: [provideHttpClient(), { provide: LOCALE_ID, useValue: 'it' }]
}).catch(err => console.error(err));
