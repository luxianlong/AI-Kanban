const sharp=require('sharp');
(async()=>{
 const {data,info}=await sharp('hi10.jpg').greyscale().raw().toBuffer({resolveWithObject:true});
 const W=info.width,H=info.height;
 const col=new Float64Array(W), row=new Float64Array(H);
 for(let y=0;y<H;y++)for(let x=0;x<W;x++)col[x]+=data[y*W+x];
 for(let x=0;x<W;x++)col[x]/=H;
 // rows measured only over the left leaf band
 for(let y=0;y<H;y++){let s=0,n=0;for(let x=400;x<1300;x++){s+=data[y*W+x];n++;}row[y]=s/n;}
 let out=[];for(let x=200;x<400;x+=5)out.push(x+':'+col[x].toFixed(0));console.log('L',out.join(' '));
 out=[];for(let x=1350;x<1700;x+=5)out.push(x+':'+col[x].toFixed(0));console.log('FOLD',out.join(' '));
 out=[];for(let y=100;y<300;y+=5)out.push(y+':'+row[y].toFixed(0));console.log('TOP',out.join(' '));
 out=[];for(let y=2100;y<2300;y+=5)out.push(y+':'+row[y].toFixed(0));console.log('BOT',out.join(' '));
})();
